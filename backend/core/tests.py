from unittest.mock import patch

from cryptography.fernet import Fernet
from django.test import TestCase, override_settings

from .models import Academia, Exercise, Machine, Student, UsageEvent


def make_pilot():
    academia = Academia.objects.create(name="Pulse Piloto", slug="pulse-piloto")
    supino = Machine.objects.create(academia=academia, number=1, name="Supino reto")
    Exercise.objects.create(machine=supino, name="Supino reto (máquina)", hevy_exercise_template_id="AAA")
    cadeira = Machine.objects.create(academia=academia, number=2, name="Cadeira adutora e abdutora")
    Exercise.objects.create(machine=cadeira, name="Cadeira abdutora", hevy_exercise_template_id="BBB")
    Exercise.objects.create(machine=cadeira, name="Cadeira adutora", hevy_exercise_template_id="CCC")
    student = Student.objects.create(academia=academia, external_id="1001", name="Aluno Teste")
    student.set_pin("4321")
    student.save()
    return academia, supino, cadeira, student


class MachineConfigTests(TestCase):
    def setUp(self):
        self.academia, self.supino, self.cadeira, self.student = make_pilot()

    def test_config_requires_device_token(self):
        resp = self.client.get("/api/v1/machine/config/")
        self.assertEqual(resp.status_code, 401)

    def test_single_exercise_machine(self):
        resp = self.client.get(
            "/api/v1/machine/config/", headers={"X-Device-Token": self.supino.device_token}
        )
        self.assertEqual(resp.status_code, 200)
        data = resp.json()
        self.assertFalse(data["machine"]["is_multifunctional"])
        self.assertEqual(len(data["exercises"]), 1)

    def test_multifunctional_machine(self):
        resp = self.client.get(
            "/api/v1/machine/config/", headers={"X-Device-Token": self.cadeira.device_token}
        )
        data = resp.json()
        self.assertTrue(data["machine"]["is_multifunctional"])
        self.assertEqual(len(data["exercises"]), 2)


class LoginTests(TestCase):
    def setUp(self):
        self.academia, self.supino, self.cadeira, self.student = make_pilot()
        self.headers = {"X-Device-Token": self.supino.device_token}

    def login(self, student_id="1001", pin="4321"):
        return self.client.post(
            "/api/v1/auth/login/",
            {"student_id": student_id, "pin": pin},
            content_type="application/json",
            headers=self.headers,
        )

    def test_login_ok(self):
        resp = self.login()
        self.assertEqual(resp.status_code, 200)
        data = resp.json()
        self.assertIn("session_token", data)
        self.assertFalse(data["student"]["hevy_linked"])
        self.assertTrue(UsageEvent.objects.filter(event_type="login").exists())

    def test_wrong_pin_rejected_and_logged(self):
        resp = self.login(pin="0000")
        self.assertEqual(resp.status_code, 401)
        self.assertTrue(UsageEvent.objects.filter(event_type="login_failed").exists())

    def test_student_from_other_academia_rejected(self):
        other = Academia.objects.create(name="Outra", slug="outra")
        stranger = Student.objects.create(academia=other, external_id="9999", name="Outro")
        stranger.set_pin("1111")
        stranger.save()
        resp = self.login(student_id="9999", pin="1111")
        self.assertEqual(resp.status_code, 401)

    def test_logout_ends_session(self):
        token = self.login().json()["session_token"]
        resp = self.client.post("/api/v1/auth/logout/", headers={"X-Session-Token": token})
        self.assertEqual(resp.status_code, 200)
        resp = self.client.post(
            "/api/v1/hevy/link/",
            {"hevy_api_key": "whatever"},
            content_type="application/json",
            headers={"X-Session-Token": token},
        )
        self.assertEqual(resp.status_code, 401)


@override_settings(HEVY_KEY_ENCRYPTION_KEY=Fernet.generate_key().decode())
class HevyLinkTests(TestCase):
    def setUp(self):
        self.academia, self.supino, self.cadeira, self.student = make_pilot()
        resp = self.client.post(
            "/api/v1/auth/login/",
            {"student_id": "1001", "pin": "4321"},
            content_type="application/json",
            headers={"X-Device-Token": self.supino.device_token},
        )
        self.session_headers = {"X-Session-Token": resp.json()["session_token"]}

    @patch("core.views.HevyClient.validate_key", return_value=True)
    def test_link_encrypts_key(self, _mock):
        resp = self.client.post(
            "/api/v1/hevy/link/",
            {"hevy_api_key": "my-secret-key"},
            content_type="application/json",
            headers=self.session_headers,
        )
        self.assertEqual(resp.status_code, 200)
        self.student.refresh_from_db()
        self.assertTrue(self.student.hevy_linked)
        self.assertNotIn("my-secret-key", self.student.hevy_api_key_encrypted)
        self.assertEqual(self.student.get_hevy_api_key(), "my-secret-key")

    @patch("core.views.HevyClient.validate_key")
    def test_invalid_key_rejected(self, mock_validate):
        from .hevy import HevyError

        mock_validate.side_effect = HevyError("bad key", status_code=401)
        resp = self.client.post(
            "/api/v1/hevy/link/",
            {"hevy_api_key": "bad"},
            content_type="application/json",
            headers=self.session_headers,
        )
        self.assertEqual(resp.status_code, 400)
        self.student.refresh_from_db()
        self.assertFalse(self.student.hevy_linked)
