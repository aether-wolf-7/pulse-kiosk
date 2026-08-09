"""Retry failed/pending Hevy pushes (Hevy instability, transient errors).

Run from cron in production, or by hand. Logs that fail again just stay
failed; nothing is ever pushed twice (status flips to pushed on success).
"""

from django.core.management.base import BaseCommand
from django.utils import timezone

from core.models import WorkoutLog
from core.sync import push_workout_log


class Command(BaseCommand):
    help = "Reenvia registros de treino que falharam no push pro Hevy"

    def add_arguments(self, parser):
        parser.add_argument("--max-age-days", type=int, default=7)

    def handle(self, *args, **options):
        cutoff = timezone.now() - timezone.timedelta(days=options["max_age_days"])
        queue = WorkoutLog.objects.filter(
            status__in=[WorkoutLog.STATUS_PENDING, WorkoutLog.STATUS_FAILED],
            logged_at__gte=cutoff,
        ).select_related("student", "machine__academia", "exercise")

        total, ok = 0, 0
        for log in queue:
            total += 1
            if push_workout_log(log):
                ok += 1
                self.stdout.write(f"  pushed: {log}")
            else:
                self.stdout.write(f"  still failing: {log} ({log.error_detail[:80]})")

        self.stdout.write(self.style.SUCCESS(f"{ok}/{total} reenviados com sucesso."))
