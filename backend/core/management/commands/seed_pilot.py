"""Seed the pilot academia with the 3 machines Jackson confirmed.

Hevy exercise_template_ids mapped 2026-08-08 against the official list
(GET /v1/exercise_templates, 451 templates) using the client's key; all
four are type weight_reps, equipment machine.
Idempotent: safe to run more than once; also fixes stale TODO ids.
"""

from django.core.management.base import BaseCommand

from core.models import Academia, Exercise, Machine

PILOT_MACHINES = [
    {
        "number": 1,
        "name": "Supino reto (seleção de peso)",
        "exercises": [("Supino reto (máquina)", "7EB3F7C3")],  # Chest Press (Machine)
    },
    {
        "number": 2,
        "name": "Cadeira adutora e abdutora (seleção de peso)",
        "exercises": [
            ("Cadeira abdutora", "F4B4C6EE"),  # Hip Abduction (Machine)
            ("Cadeira adutora", "8BEBFED6"),  # Hip Adduction (Machine)
        ],
    },
    {
        "number": 3,
        "name": "Abdominal (seleção de peso)",
        "exercises": [("Abdominal (máquina)", "EB43ADD4")],  # Crunch (Machine)
    },
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
            for ex_name, template_id in spec["exercises"]:
                exercise, _ = Exercise.objects.get_or_create(
                    machine=machine,
                    name=ex_name,
                    defaults={"hevy_exercise_template_id": template_id},
                )
                if exercise.hevy_exercise_template_id != template_id:
                    exercise.hevy_exercise_template_id = template_id
                    exercise.save(update_fields=["hevy_exercise_template_id"])
            self.stdout.write(
                f"  Máquina {machine.number}: {machine.name} "
                f"({'criada' if m_created else 'já existia'}, "
                f"multifuncional={machine.is_multifunctional})"
            )
            self.stdout.write(f"    device_token: {machine.device_token}")

        self.stdout.write(self.style.SUCCESS("Seed do piloto concluído."))
