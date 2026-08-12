"""Concurrency probe against a real PostgreSQL database.

SQLite serialises writers, so the unit suite cannot exercise the two races
that matter once three tablets share one backend:

  A. two workers pushing the SAME WorkoutLog (immediate push racing the
     retry command) must produce exactly ONE Hevy workout;
  B. two simultaneous submits of the SAME client_uuid (offline resend
     arriving twice) must produce exactly ONE WorkoutLog and no 500.

Run with DATABASE_URL pointing at a throwaway Postgres database.
"""

import os
import threading
import time
import uuid
from unittest.mock import patch

import django

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings")
django.setup()

from django.db import connections  # noqa: E402
from django.test import Client  # noqa: E402

from core.models import (  # noqa: E402
    Academia,
    Exercise,
    Machine,
    Student,
    StudentSession,
    UsageEvent,
    WorkoutLog,
)
from core.sync import push_workout_log  # noqa: E402

THREADS = 8
call_lock = threading.Lock()
call_count = 0


def fake_create_workout(self, **kwargs):
    """Stand-in for the Hevy API that counts calls and is slow enough to
    make a real race window."""
    global call_count
    with call_lock:
        call_count += 1
    time.sleep(0.4)
    return {"workout": {"id": "HW-CONCURRENT"}}


def reset_data():
    WorkoutLog.objects.all().delete()
    UsageEvent.objects.all().delete()
    StudentSession.objects.all().delete()
    Student.objects.all().delete()
    Machine.objects.all().delete()
    Academia.objects.all().delete()

    academia = Academia.objects.create(name="Probe", slug="probe")
    machine = Machine.objects.create(academia=academia, number=1, name="Supino")
    exercise = Exercise.objects.create(
        machine=machine, name="Supino", hevy_exercise_template_id="AAA"
    )
    student = Student.objects.create(academia=academia, external_id="1", name="Probe")
    student.set_pin("1234")
    student.set_hevy_api_key("probe-key")
    student.save()
    return academia, machine, exercise, student


def probe_double_push(machine, exercise, student):
    """A: many workers, one WorkoutLog -> exactly one Hevy call."""
    global call_count
    call_count = 0
    log = WorkoutLog.objects.create(
        client_uuid=uuid.uuid4(),
        student=student,
        machine=machine,
        exercise=exercise,
        sets=[{"weight_kg": 20, "reps": 10}],
    )

    def worker():
        try:
            push_workout_log(WorkoutLog.objects.get(pk=log.pk))
        finally:
            connections.close_all()

    threads = [threading.Thread(target=worker) for _ in range(THREADS)]
    barrier_start = time.time()
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    log.refresh_from_db()
    ok = call_count == 1 and log.status == WorkoutLog.STATUS_PUSHED
    print(f"A. double-push   : hevy_calls={call_count} (want 1), status={log.status}, "
          f"{time.time() - barrier_start:.1f}s -> {'PASS' if ok else 'FAIL'}")
    return ok


def probe_duplicate_submit(machine, exercise, student):
    """B: many simultaneous submits of one client_uuid -> one row, no 500."""
    global call_count
    call_count = 0
    WorkoutLog.objects.all().delete()
    session = StudentSession.objects.create(student=student, machine=machine)
    cid = str(uuid.uuid4())
    body = {
        "client_uuid": cid,
        "exercise_id": exercise.id,
        "sets": [{"weight_kg": 30, "reps": 8}],
    }
    statuses = []
    status_lock = threading.Lock()

    def worker():
        try:
            resp = Client().post(
                "/api/v1/workouts/",
                body,
                content_type="application/json",
                headers={"X-Session-Token": session.token},
            )
            with status_lock:
                statuses.append(resp.status_code)
        except Exception as exc:  # a 500 surfaces here as an exception
            with status_lock:
                statuses.append(f"EXC:{type(exc).__name__}")
        finally:
            connections.close_all()

    threads = [threading.Thread(target=worker) for _ in range(THREADS)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    rows = WorkoutLog.objects.filter(client_uuid=cid).count()
    bad = [s for s in statuses if not isinstance(s, int) or s >= 500]
    ok = rows == 1 and not bad and call_count <= 1
    print(f"B. dup submit    : rows={rows} (want 1), hevy_calls={call_count} (want <=1), "
          f"statuses={sorted(statuses, key=str)} -> {'PASS' if ok else 'FAIL'}")
    return ok


if __name__ == "__main__":
    from django.db import connection

    assert "postgresql" in connection.settings_dict["ENGINE"], "point DATABASE_URL at Postgres"
    print(f"database: {connection.settings_dict['NAME']} ({connection.settings_dict['ENGINE']})\n")

    academia, machine, exercise, student = reset_data()
    with patch("core.hevy.HevyClient.create_workout", fake_create_workout):
        a = probe_double_push(machine, exercise, student)
        b = probe_duplicate_submit(machine, exercise, student)

    print("\nRESULT:", "ALL PASS" if (a and b) else "FAILURES PRESENT")
    raise SystemExit(0 if (a and b) else 1)
