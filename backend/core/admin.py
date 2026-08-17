from django import forms
from django.contrib import admin, messages
from django.utils import timezone
from django.utils.html import format_html, format_html_join

from .models import (
    Academia,
    Exercise,
    Machine,
    PairingCode,
    Student,
    UsageEvent,
    WorkoutLog,
)

admin.site.site_header = "Pulse Kiosk — Painel Administrativo"
admin.site.site_title = "Pulse Kiosk"
admin.site.index_title = "Gestão de alunos, máquinas e exercícios"


@admin.register(Academia)
class AcademiaAdmin(admin.ModelAdmin):
    list_display = ("name", "slug", "admin_pin", "is_active", "created_at")
    prepopulated_fields = {"slug": ("name",)}
    fields = ("name", "slug", "admin_pin", "is_active")


class ExerciseInline(admin.TabularInline):
    model = Exercise
    extra = 1


@admin.register(Machine)
class MachineAdmin(admin.ModelAdmin):
    list_display = (
        "number",
        "name",
        "academia",
        "is_multifunctional",
        "is_active",
        "pairing_code",
    )
    list_filter = ("academia", "is_active")
    inlines = [ExerciseInline]
    readonly_fields = ("device_token", "pairing_code")
    actions = ["generate_pairing_code"]

    @admin.display(boolean=True, description="Multifuncional")
    def is_multifunctional(self, obj):
        return obj.is_multifunctional

    @admin.display(description="Código de pareamento")
    def pairing_code(self, obj):
        code = obj.pairing_codes.filter(used_at__isnull=True).first()
        if code and code.is_valid:
            minutes = int((code.expires_at - timezone.now()).total_seconds() // 60)
            return format_html(
                '<b style="font-size:1.3em;letter-spacing:2px">{}</b>'
                '<br><small>expira em {} min</small>',
                code.code,
                minutes,
            )
        return format_html('<small style="color:#888">selecione e use a ação acima</small>')

    @admin.action(description="Gerar código de pareamento para o tablet")
    def generate_pairing_code(self, request, queryset):
        """One click per machine on install day: the technician reads six
        digits off the screen instead of typing a 43 character token."""
        issued = [(m.name, PairingCode.issue(m).code) for m in queryset]
        self.message_user(
            request,
            format_html(
                "Código gerado (válido por {} min): {}",
                PairingCode.TTL_MINUTES,
                format_html_join(" | ", "{} = <b>{}</b>", issued),
            ),
            messages.SUCCESS,
        )


class StudentForm(forms.ModelForm):
    pin = forms.CharField(
        label="PIN",
        required=False,
        widget=forms.PasswordInput,
        help_text="Preencha para definir ou trocar o PIN do aluno. Fica salvo apenas como hash.",
    )

    class Meta:
        model = Student
        fields = ("academia", "external_id", "name", "is_active")

    def save(self, commit=True):
        student = super().save(commit=False)
        pin = self.cleaned_data.get("pin")
        if pin:
            student.set_pin(pin)
        if commit:
            student.save()
        return student


@admin.register(Student)
class StudentAdmin(admin.ModelAdmin):
    form = StudentForm
    list_display = ("external_id", "name", "academia", "hevy_linked", "is_active", "created_at")
    list_filter = ("academia", "is_active")
    search_fields = ("external_id", "name")

    @admin.display(boolean=True, description="Hevy vinculado")
    def hevy_linked(self, obj):
        return obj.hevy_linked


@admin.register(WorkoutLog)
class WorkoutLogAdmin(admin.ModelAdmin):
    list_display = ("student", "machine", "exercise", "status", "logged_at", "pushed_at")
    list_filter = ("status", "machine__academia")
    readonly_fields = [f.name for f in WorkoutLog._meta.fields]

    def has_add_permission(self, request):
        return False


@admin.register(UsageEvent)
class UsageEventAdmin(admin.ModelAdmin):
    list_display = ("event_type", "academia", "student", "machine", "created_at")
    list_filter = ("event_type", "academia")
    readonly_fields = [f.name for f in UsageEvent._meta.fields]

    def has_add_permission(self, request):
        return False


@admin.register(PairingCode)
class PairingCodeAdmin(admin.ModelAdmin):
    list_display = ("code", "machine", "created_at", "expires_at", "used_at", "is_valid")
    list_filter = ("machine__academia",)
    readonly_fields = [f.name for f in PairingCode._meta.fields]

    @admin.display(boolean=True, description="Válido")
    def is_valid(self, obj):
        return obj.is_valid

    def has_add_permission(self, request):
        # Codes are issued from the machine list, never hand-typed.
        return False
