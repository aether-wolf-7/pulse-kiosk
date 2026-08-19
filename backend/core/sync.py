"""Push WorkoutLogs to the students' Hevy accounts.

Shared by the submit endpoint (immediate push) and the retry management
command (Hevy instability / transient failures). A log is only ever
pushed once: client_uuid is unique, and each attempt claims the row by
flipping its status to `pushing`, so a retry run cannot double-post a
workout that an in-flight request is already sending.
"""

import logging

from django.utils import timezone

from .crypto import InvalidToken
from .hevy import HevyClient, HevyError
from .models import UsageEvent, WorkoutLog

logger = logging.getLogger(__name__)


def _fail(log: WorkoutLog, detail: str) -> bool:
    log.status = WorkoutLog.STATUS_FAILED
    log.error_detail = detail
    log.save(update_fields=["status", "error_detail"])
    UsageEvent.objects.create(
        academia=log.machine.academia,
        student=log.student,
        machine=log.machine,
        event_type="hevy_push_failed",
        metadata={"client_uuid": str(log.client_uuid), "error": detail},
    )
    return False


def push_workout_log(log: WorkoutLog) -> bool:
    """Attempt to push one log to Hevy. Updates status/pushed_at/error_detail.
    Returns True on success."""
    # Atomic claim: only one worker may move a row out of pending/failed.
    claimed = WorkoutLog.objects.filter(
        pk=log.pk, status__in=[WorkoutLog.STATUS_PENDING, WorkoutLog.STATUS_FAILED]
    ).update(status=WorkoutLog.STATUS_PUSHING, claimed_at=timezone.now())
    if not claimed:
        logger.info("Skipping %s: already claimed or pushed", log.client_uuid)
        return False
    log.status = WorkoutLog.STATUS_PUSHING

    student = log.student
    # Check the raw field, not hevy_linked: that now reports False for an
    # unreadable key too, and the two cases need different guidance.
    if not student.hevy_api_key_encrypted:
        return _fail(log, "Aluno sem conta Hevy vinculada")

    try:
        api_key = student.get_hevy_api_key()
    except InvalidToken:
        # Stored under a different encryption key (rotation, or a dev key
        # carried across environments). Unrecoverable without re-linking.
        logger.error("Cannot decrypt Hevy key for student %s", student.pk)
        return _fail(log, "Chave do Hevy ilegível, aluno precisa vincular de novo")

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
        result = HevyClient(api_key).create_workout(
            title=title,
            exercises=exercises,
            start_time=start.isoformat(),
            end_time=end.isoformat(),
        )
    except HevyError as exc:
        return _fail(log, str(exc))
    except Exception as exc:  # never leave a row stuck in `pushing`
        logger.exception("Unexpected error pushing %s", log.client_uuid)
        return _fail(log, f"Erro inesperado: {exc}")

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
