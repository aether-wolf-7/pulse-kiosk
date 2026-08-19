"""Retry failed/pending Hevy pushes (Hevy instability, transient errors).

Run from cron in production, or by hand. Nothing is ever pushed twice:
push_workout_log claims each row atomically, and rows younger than
--min-age-minutes are skipped so a run cannot race the immediate push
that the submit endpoint is still performing.
"""

from django.core.management.base import BaseCommand
from django.db.models import Q
from django.utils import timezone

from core.models import WorkoutLog
from core.sync import push_workout_log

# A push that has been "pushing" for longer than this crashed mid-flight.
STALE_PUSHING_MINUTES = 15


class Command(BaseCommand):
    help = "Reenvia registros de treino que falharam no push pro Hevy"

    def add_arguments(self, parser):
        parser.add_argument("--max-age-days", type=int, default=7)
        parser.add_argument(
            "--min-age-minutes",
            type=int,
            default=2,
            help="Ignora registros recentes, que ainda podem estar sendo enviados.",
        )

    def handle(self, *args, **options):
        now = timezone.now()
        cutoff_old = now - timezone.timedelta(days=options["max_age_days"])
        cutoff_recent = now - timezone.timedelta(minutes=options["min_age_minutes"])
        stale_pushing = now - timezone.timedelta(minutes=STALE_PUSHING_MINUTES)

        queue = (
            WorkoutLog.objects.filter(logged_at__gte=cutoff_old, logged_at__lte=cutoff_recent)
            .filter(
                Q(status__in=[WorkoutLog.STATUS_PENDING, WorkoutLog.STATUS_FAILED])
                | Q(status=WorkoutLog.STATUS_PUSHING, claimed_at__lte=stale_pushing)
            )
            .select_related("student", "machine__academia", "exercise")
        )

        # Recover rows abandoned by a crashed push so they can be claimed again.
        WorkoutLog.objects.filter(
            status=WorkoutLog.STATUS_PUSHING, claimed_at__lte=stale_pushing
        ).update(status=WorkoutLog.STATUS_FAILED, error_detail="Envio interrompido, reenfileirado")

        total, ok = 0, 0
        for log in queue:
            total += 1
            log.refresh_from_db()
            if push_workout_log(log):
                ok += 1
                self.stdout.write(f"  pushed: {log}")
            else:
                self.stdout.write(f"  still failing: {log} ({log.error_detail[:80]})")

        self.stdout.write(self.style.SUCCESS(f"{ok}/{total} reenviados com sucesso."))
