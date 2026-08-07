import secrets
import uuid

from django.contrib.auth.hashers import check_password, make_password
from django.db import models
from django.utils import timezone

from . import crypto


class Academia(models.Model):
    """Tenant. Every domain object hangs off an academia so the same
    backend serves the pilot gym today and a fleet of gyms later."""

    name = models.CharField(max_length=120)
    slug = models.SlugField(unique=True)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        verbose_name = "academia"
        verbose_name_plural = "academias"

    def __str__(self):
        return self.name


class Machine(models.Model):
    academia = models.ForeignKey(Academia, on_delete=models.CASCADE, related_name="machines")
    number = models.PositiveIntegerField(help_text="Número da máquina na academia")
    name = models.CharField(max_length=120)
    # Tablets authenticate with this token (X-Device-Token) and are locked
    # to one machine. Rotate in the admin if a tablet is compromised.
    device_token = models.CharField(max_length=64, unique=True, editable=False)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        unique_together = [("academia", "number")]
        verbose_name = "máquina"
        verbose_name_plural = "máquinas"

    def save(self, *args, **kwargs):
        if not self.device_token:
            self.device_token = secrets.token_urlsafe(32)
        super().save(*args, **kwargs)

    @property
    def is_multifunctional(self) -> bool:
        return self.exercises.filter(is_active=True).count() > 1

    def __str__(self):
        return f"{self.number} - {self.name} ({self.academia.slug})"


class Exercise(models.Model):
    machine = models.ForeignKey(Machine, on_delete=models.CASCADE, related_name="exercises")
    name = models.CharField(max_length=120)
    hevy_exercise_template_id = models.CharField(
        max_length=32,
        help_text="ID do exercise template na API do Hevy (ex: 1E9A6B8E)",
    )
    is_active = models.BooleanField(default=True)

    class Meta:
        verbose_name = "exercício"
        verbose_name_plural = "exercícios"

    def __str__(self):
        return f"{self.name} ({self.machine.name})"


class Student(models.Model):
    academia = models.ForeignKey(Academia, on_delete=models.CASCADE, related_name="students")
    external_id = models.CharField(max_length=32, help_text="ID que o aluno digita no tablet")
    name = models.CharField(max_length=120)
    pin_hash = models.CharField(max_length=128, editable=False)
    hevy_api_key_encrypted = models.TextField(blank=True, default="", editable=False)
    hevy_linked_at = models.DateTimeField(null=True, blank=True)
    is_active = models.BooleanField(default=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        unique_together = [("academia", "external_id")]
        verbose_name = "aluno"
        verbose_name_plural = "alunos"

    def set_pin(self, raw_pin: str):
        self.pin_hash = make_password(raw_pin)

    def check_pin(self, raw_pin: str) -> bool:
        return check_password(raw_pin, self.pin_hash)

    @property
    def hevy_linked(self) -> bool:
        return bool(self.hevy_api_key_encrypted)

    def set_hevy_api_key(self, raw_key: str):
        self.hevy_api_key_encrypted = crypto.encrypt(raw_key)
        self.hevy_linked_at = timezone.now()

    def get_hevy_api_key(self) -> str:
        return crypto.decrypt(self.hevy_api_key_encrypted)

    def __str__(self):
        return f"{self.external_id} - {self.name}"


class StudentSession(models.Model):
    """Short-lived kiosk session: created on ID+PIN login, killed on save
    or timeout so the next student never sees the previous one's data."""

    token = models.CharField(max_length=64, unique=True, editable=False)
    student = models.ForeignKey(Student, on_delete=models.CASCADE, related_name="sessions")
    machine = models.ForeignKey(Machine, on_delete=models.CASCADE, related_name="sessions")
    created_at = models.DateTimeField(auto_now_add=True)
    expires_at = models.DateTimeField()
    ended_at = models.DateTimeField(null=True, blank=True)

    SESSION_TTL_MINUTES = 10

    def save(self, *args, **kwargs):
        if not self.token:
            self.token = secrets.token_urlsafe(32)
        if not self.expires_at:
            self.expires_at = timezone.now() + timezone.timedelta(minutes=self.SESSION_TTL_MINUTES)
        super().save(*args, **kwargs)

    @property
    def is_valid(self) -> bool:
        return self.ended_at is None and timezone.now() < self.expires_at

    def end(self):
        self.ended_at = timezone.now()
        self.save(update_fields=["ended_at"])


class WorkoutLog(models.Model):
    """One machine visit: the sets a student logged, and the state of the
    push to their Hevy account. client_uuid makes offline resends idempotent."""

    STATUS_PENDING = "pending"
    STATUS_PUSHED = "pushed"
    STATUS_FAILED = "failed"
    STATUS_CHOICES = [
        (STATUS_PENDING, "Pendente"),
        (STATUS_PUSHED, "Enviado ao Hevy"),
        (STATUS_FAILED, "Falha no envio"),
    ]

    client_uuid = models.UUIDField(default=uuid.uuid4, unique=True)
    student = models.ForeignKey(Student, on_delete=models.CASCADE, related_name="workout_logs")
    machine = models.ForeignKey(Machine, on_delete=models.CASCADE, related_name="workout_logs")
    exercise = models.ForeignKey(Exercise, on_delete=models.PROTECT, related_name="workout_logs")
    # [{"weight_kg": 40, "reps": 12}, ...]
    sets = models.JSONField(default=list)
    status = models.CharField(max_length=12, choices=STATUS_CHOICES, default=STATUS_PENDING)
    hevy_workout_id = models.CharField(max_length=64, blank=True, default="")
    error_detail = models.TextField(blank=True, default="")
    logged_at = models.DateTimeField(default=timezone.now)
    pushed_at = models.DateTimeField(null=True, blank=True)

    class Meta:
        verbose_name = "registro de treino"
        verbose_name_plural = "registros de treino"

    def __str__(self):
        return f"{self.student.external_id} @ {self.machine.name} ({self.status})"


class UsageEvent(models.Model):
    """Raw metric stream: pilot 'acceptance' becomes data (activations,
    retention, entries per workout) instead of opinion."""

    EVENT_CHOICES = [
        ("login", "Login"),
        ("login_failed", "Login falhou"),
        ("hevy_linked", "Conta Hevy vinculada"),
        ("workout_logged", "Treino registrado"),
        ("hevy_pushed", "Enviado ao Hevy"),
        ("hevy_push_failed", "Falha no envio ao Hevy"),
        ("session_expired", "Sessão expirada"),
    ]

    academia = models.ForeignKey(Academia, on_delete=models.CASCADE, related_name="events")
    student = models.ForeignKey(Student, null=True, blank=True, on_delete=models.SET_NULL)
    machine = models.ForeignKey(Machine, null=True, blank=True, on_delete=models.SET_NULL)
    event_type = models.CharField(max_length=32, choices=EVENT_CHOICES)
    metadata = models.JSONField(default=dict, blank=True)
    created_at = models.DateTimeField(auto_now_add=True)

    class Meta:
        indexes = [models.Index(fields=["academia", "event_type", "created_at"])]
        verbose_name = "evento de uso"
        verbose_name_plural = "eventos de uso"

    def __str__(self):
        return f"{self.event_type} @ {self.created_at:%Y-%m-%d %H:%M}"
