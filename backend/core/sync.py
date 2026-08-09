"""Push WorkoutLogs to the students' Hevy accounts.

Shared by the submit endpoint (immediate push) and the retry management
command (Hevy instability / transient failures). A log is only ever
pushed once: client_uuid is unique and status guards re-entry.
"""

import logging

from django.utils import timezone

from .hevy import HevyClient, HevyError
from .models import UsageEvent, WorkoutLog

logger = logging.getLogger(__name__)


def push_workout_log(log: WorkoutLog) -> bool:
    """Attempt to push one log to Hevy. Updates status/pushed_at/error_detail.
    Returns True on success."""
    student = log.student
    if not student.hevy_linked:
        log.status = WorkoutLog.STATUS_FAILED
        log.error_detail = "Aluno sem conta Hevy vinculada"
        log.save(update_fields=["status", "error_detail"])
        return False

    start = log.logged_at
    # Hevy requires an interval; a machine visit is short, call it 5 minutes.
    end = start + timezone.timedelta(minutes=5)
    exercises = [
        {
            "exercise_template_id": log.exercise.hevy_exercise_template_id,
            "sets": [
                {"type": "normal", "weight_kg": s["weight_kg"], "reps": s["reps"]}
                for s in log.sets
            ],
        }
    ]
    title = f"{log.exercise.name} - {log.machine.academia.name}"

    try:
        result = HevyClient(student.get_hevy_api_key()).create_workout(
            title=title,
            exercises=exercises,
            start_time=start.isoformat(),
            end_time=end.isoformat(),
        )
    except HevyError as exc:
        log.status = WorkoutLog.STATUS_FAILED
        log.error_detail = str(exc)
        log.save(update_fields=["status", "error_detail"])
        UsageEvent.objects.create(
            academia=log.machine.academia,
            student=student,
            machine=log.machine,
            event_type="hevy_push_failed",
            metadata={"client_uuid": str(log.client_uuid), "error": str(exc)},
        )
        return False

    workout = result.get("workout") or {}
    if isinstance(workout, list):  # docs show both shapes; tolerate either
        workout = workout[0] if workout else {}
    log.hevy_workout_id = str(workout.get("id", ""))
    log.status = WorkoutLog.STATUS_PUSHED
    log.pushed_at = timezone.now()
    log.error_detail = ""
    log.save(update_fields=["hevy_workout_id", "status", "pushed_at", "error_detail"])
    UsageEvent.objects.create(
        academia=log.machine.academia,
        student=student,
        machine=log.machine,
        event_type="hevy_pushed",
        metadata={"client_uuid": str(log.client_uuid), "hevy_workout_id": log.hevy_workout_id},
    )
    return True
