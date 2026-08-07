from django import forms
from django.contrib import admin

from .models import Academia, Exercise, Machine, Student, UsageEvent, WorkoutLog

admin.site.site_header = "Pulse Kiosk — Painel Administrativo"
admin.site.site_title = "Pulse Kiosk"
admin.site.index_title = "Gestão de alunos, máquinas e exercícios"


@admin.register(Academia)
class AcademiaAdmin(admin.ModelAdmin):
    list_display = ("name", "slug", "is_active", "created_at")
    prepopulated_fields = {"slug": ("name",)}


class ExerciseInline(admin.TabularInline):
    model = Exercise
    extra = 1


@admin.register(Machine)
class MachineAdmin(admin.ModelAdmin):
    list_display = ("number", "name", "academia", "is_multifunctional", "is_active", "device_token")
    list_filter = ("academia", "is_active")
    inlines = [ExerciseInline]
    readonly_fields = ("device_token",)

    @admin.display(boolean=True, description="Multifuncional")
    def is_multifunctional(self, obj):
        return obj.is_multifunctional


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
