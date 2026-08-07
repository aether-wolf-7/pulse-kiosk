import logging

from django.utils import timezone
from rest_framework import status
from rest_framework.response import Response
from rest_framework.views import APIView

from .hevy import HevyClient, HevyError
from .models import Machine, Student, StudentSession, UsageEvent

logger = logging.getLogger(__name__)


def get_machine(request):
    token = request.headers.get("X-Device-Token", "")
    if not token:
        return None
    return (
        Machine.objects.filter(device_token=token, is_active=True, academia__is_active=True)
        .select_related("academia")
        .first()
    )


def get_session(request):
    token = request.headers.get("X-Session-Token", "")
    if not token:
        return None
    session = (
        StudentSession.objects.filter(token=token)
        .select_related("student", "machine", "machine__academia")
        .first()
    )
    if session and session.is_valid:
        return session
    return None


class MachineConfigView(APIView):
    """Tablet boot: resolves the device token into machine + exercises."""

    def get(self, request):
        machine = get_machine(request)
        if not machine:
            return Response({"detail": "Tablet não registrado"}, status=status.HTTP_401_UNAUTHORIZED)
        exercises = [
            {"id": e.id, "name": e.name, "hevy_exercise_template_id": e.hevy_exercise_template_id}
            for e in machine.exercises.filter(is_active=True)
        ]
        return Response(
            {
                "academia": {"slug": machine.academia.slug, "name": machine.academia.name},
                "machine": {
                    "id": machine.id,
                    "number": machine.number,
                    "name": machine.name,
                    "is_multifunctional": machine.is_multifunctional,
                },
                "exercises": exercises,
            }
        )


class LoginView(APIView):
    """ID + PIN login, scoped to the tablet's academia."""

    def post(self, request):
        machine = get_machine(request)
        if not machine:
            return Response({"detail": "Tablet não registrado"}, status=status.HTTP_401_UNAUTHORIZED)

        external_id = str(request.data.get("student_id", "")).strip()
        pin = str(request.data.get("pin", ""))
        generic_error = Response(
            {"detail": "ID ou PIN incorretos"}, status=status.HTTP_401_UNAUTHORIZED
        )
        if not external_id or not pin:
            return generic_error

        student = Student.objects.filter(
            academia=machine.academia, external_id=external_id, is_active=True
        ).first()
        if not student or not student.check_pin(pin):
            UsageEvent.objects.create(
                academia=machine.academia,
                machine=machine,
                student=student,
                event_type="login_failed",
            )
            return generic_error

        session = StudentSession.objects.create(student=student, machine=machine)
        UsageEvent.objects.create(
            academia=machine.academia, machine=machine, student=student, event_type="login"
        )
        return Response(
            {
                "session_token": session.token,
                "expires_at": session.expires_at,
                "student": {"name": student.name, "hevy_linked": student.hevy_linked},
            }
        )


class LogoutView(APIView):
    def post(self, request):
        session = get_session(request)
        if session:
            session.end()
        return Response({"detail": "ok"})


class HevyLinkView(APIView):
    """First access: validate the student's Hevy Pro key live against the
    Hevy API, then store it encrypted. From then on, ID + PIN is enough."""

    def post(self, request):
        session = get_session(request)
        if not session:
            return Response({"detail": "Sessão inválida ou expirada"}, status=status.HTTP_401_UNAUTHORIZED)

        api_key = str(request.data.get("hevy_api_key", "")).strip()
        if not api_key:
            return Response({"detail": "Informe a API key do Hevy"}, status=status.HTTP_400_BAD_REQUEST)

        try:
            HevyClient(api_key).validate_key()
        except HevyError as exc:
            if exc.status_code == 401:
                return Response(
                    {"detail": "API key inválida. Confira no app do Hevy (requer Hevy Pro)."},
                    status=status.HTTP_400_BAD_REQUEST,
                )
            return Response(
                {"detail": "Hevy fora do ar no momento, tente novamente"},
                status=status.HTTP_503_SERVICE_UNAVAILABLE,
            )

        student = session.student
        student.set_hevy_api_key(api_key)
        student.save(update_fields=["hevy_api_key_encrypted", "hevy_linked_at"])
        UsageEvent.objects.create(
            academia=student.academia,
            machine=session.machine,
            student=student,
            event_type="hevy_linked",
        )
        return Response({"detail": "Conta Hevy vinculada", "hevy_linked_at": student.hevy_linked_at})


class HealthView(APIView):
    def get(self, request):
        return Response({"status": "ok", "time": timezone.now()})
