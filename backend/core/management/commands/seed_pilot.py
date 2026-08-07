"""Seed the pilot academia with the 3 machines Jackson confirmed.

Hevy exercise_template_ids are placeholders (TODO) until we map them
against GET /v1/exercise_templates with the client's key in Stage 2.
Idempotent: safe to run more than once.
"""

from django.core.management.base import BaseCommand

from core.models import Academia, Exercise, Machine

PILOT_MACHINES = [
    {"number": 1, "name": "Supino reto (seleção de peso)", "exercises": ["Supino reto (máquina)"]},
    {
        "number": 2,
        "name": "Cadeira adutora e abdutora (seleção de peso)",
        "exercises": ["Cadeira abdutora", "Cadeira adutora"],
    },
    {"number": 3, "name": "Abdominal (seleção de peso)", "exercises": ["Abdominal (máquina)"]},
]


class Command(BaseCommand):
    help = "Cria a academia piloto com as 3 máquinas do escopo"

    def handle(self, *args, **options):
        academia, created = Academia.objects.get_or_create(
            slug="pulse-piloto", defaults={"name": "Pulse Fitness — Academia Piloto"}
        )
        self.stdout.write(f"Academia: {academia} ({'criada' if created else 'já existia'})")

        for spec in PILOT_MACHINES:
            machine, m_created = Machine.objects.get_or_create(
                academia=academia, number=spec["number"], defaults={"name": spec["name"]}
            )
            for ex_name in spec["exercises"]:
                Exercise.objects.get_or_create(
                    machine=machine,
                    name=ex_name,
                    defaults={"hevy_exercise_template_id": "TODO"},
                )
            self.stdout.write(
                f"  Máquina {machine.number}: {machine.name} "
                f"({'criada' if m_created else 'já existia'}, "
                f"multifuncional={machine.is_multifunctional})"
            )
            self.stdout.write(f"    device_token: {machine.device_token}")

        self.stdout.write(self.style.SUCCESS("Seed do piloto concluído."))
