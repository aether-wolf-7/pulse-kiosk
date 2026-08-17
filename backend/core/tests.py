import uuid
from unittest.mock import patch

from cryptography.fernet import Fernet
from django.conf import settings
from django.test import TestCase, override_settings
from django.utils import timezone

from .models import Academia, Exercise, Machine, Student, StudentSession, UsageEvent, WorkoutLog
from .sync import push_workout_log


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


@override_settings(HEVY_KEY_ENCRYPTION_KEY=Fernet.generate_key().decode())
class WorkoutSubmitTests(TestCase):
    def setUp(self):
        self.academia, self.supino, self.cadeira, self.student = make_pilot()
        self.student.set_hevy_api_key("linked-key")
        self.student.save()
        self.exercise = self.supino.exercises.first()
        self.session = StudentSession.objects.create(student=self.student, machine=self.supino)

    def submit(self, token=None, **overrides):
        body = {
            "client_uuid": str(uuid.uuid4()),
            "exercise_id": self.exercise.id,
            "sets": [{"weight_kg": 40, "reps": 12}, {"weight_kg": 45, "reps": 10}],
            "logged_at": timezone.now().isoformat(),
        }
        body.update(overrides)
        return self.client.post(
            "/api/v1/workouts/",
            body,
            content_type="application/json",
            headers={"X-Session-Token": token or self.session.token},
        )

    @patch("core.sync.HevyClient.create_workout", return_value={"workout": {"id": "HW1"}})
    def test_submit_pushes_to_hevy(self, mock_create):
        resp = self.submit()
        self.assertEqual(resp.status_code, 201)
        data = resp.json()
        self.assertEqual(data["status"], "pushed")
        self.assertEqual(data["hevy_workout_id"], "HW1")
        payload = mock_create.call_args.kwargs
        self.assertEqual(payload["exercises"][0]["exercise_template_id"], "AAA")
        self.assertEqual(len(payload["exercises"][0]["sets"]), 2)
        self.assertTrue(UsageEvent.objects.filter(event_type="hevy_pushed").exists())

    @patch("core.sync.HevyClient.create_workout", return_value={"workout": {"id": "HW1"}})
    def test_resend_same_uuid_is_idempotent(self, mock_create):
        cid = str(uuid.uuid4())
        first = self.submit(client_uuid=cid)
        second = self.submit(client_uuid=cid)
        self.assertEqual(first.status_code, 201)
        self.assertEqual(second.status_code, 200)
        self.assertTrue(second.json()["duplicate"])
        self.assertEqual(WorkoutLog.objects.filter(client_uuid=cid).count(), 1)
        self.assertEqual(mock_create.call_count, 1)

    @patch("core.sync.HevyClient.create_workout")
    def test_hevy_failure_marks_failed_but_accepts(self, mock_create):
        from .hevy import HevyError

        mock_create.side_effect = HevyError("boom", status_code=500)
        resp = self.submit()
        self.assertEqual(resp.status_code, 201)
        self.assertEqual(resp.json()["status"], "failed")
        self.assertTrue(UsageEvent.objects.filter(event_type="hevy_push_failed").exists())

    def _failed_log(self, minutes_ago=10, **overrides):
        fields = dict(
            client_uuid=uuid.uuid4(),
            student=self.student,
            machine=self.supino,
            exercise=self.exercise,
            sets=[{"weight_kg": 30, "reps": 15}],
            status=WorkoutLog.STATUS_FAILED,
            error_detail="old failure",
            logged_at=timezone.now() - timezone.timedelta(minutes=minutes_ago),
        )
        fields.update(overrides)
        return WorkoutLog.objects.create(**fields)

    @patch("core.sync.HevyClient.create_workout", return_value={"workout": {"id": "HW2"}})
    def test_retry_command_pushes_failed_logs(self, mock_create):
        log = self._failed_log()
        from django.core.management import call_command

        call_command("retry_hevy_pushes")
        log.refresh_from_db()
        self.assertEqual(log.status, WorkoutLog.STATUS_PUSHED)
        self.assertEqual(log.hevy_workout_id, "HW2")

    @patch("core.sync.HevyClient.create_workout", return_value={"workout": {"id": "HW3"}})
    def test_retry_skips_recent_logs_still_being_pushed(self, mock_create):
        """A log saved seconds ago may have an in-flight push; retrying it
        would post the same workout to Hevy twice."""
        log = self._failed_log(minutes_ago=0, status=WorkoutLog.STATUS_PENDING)
        from django.core.management import call_command

        call_command("retry_hevy_pushes")
        log.refresh_from_db()
        self.assertEqual(log.status, WorkoutLog.STATUS_PENDING)
        mock_create.assert_not_called()

    @patch("core.sync.HevyClient.create_workout", return_value={"workout": {"id": "HW4"}})
    def test_push_claims_row_so_concurrent_retry_cannot_double_post(self, mock_create):
        log = self._failed_log(status=WorkoutLog.STATUS_PUSHING)
        # A row already claimed by an in-flight push must be left alone.
        self.assertFalse(push_workout_log(log))
        mock_create.assert_not_called()

    @patch("core.sync.HevyClient.create_workout", return_value={"workout": {"id": "HW5"}})
    def test_stale_pushing_row_is_recovered(self, mock_create):
        log = self._failed_log(minutes_ago=30, status=WorkoutLog.STATUS_PUSHING)
        from django.core.management import call_command

        call_command("retry_hevy_pushes")
        log.refresh_from_db()
        self.assertEqual(log.status, WorkoutLog.STATUS_PUSHED)

    @patch("core.sync.HevyClient.create_workout")
    def test_undecryptable_key_fails_cleanly(self, mock_create):
        """After an encryption key rotation the stored key cannot be read;
        that must mark the log failed, not raise a 500."""
        self.student.hevy_api_key_encrypted = Fernet(Fernet.generate_key()).encrypt(b"x").decode()
        self.student.save()
        resp = self.submit()
        self.assertEqual(resp.status_code, 201)
        self.assertEqual(resp.json()["status"], "failed")
        mock_create.assert_not_called()
        self.assertIn("vincular de novo", WorkoutLog.objects.first().error_detail)

    @patch("core.sync.HevyClient.create_workout", return_value={"workout": {"id": "HW1"}})
    def test_expired_session_within_grace_accepted(self, _mock):
        StudentSession.objects.filter(pk=self.session.pk).update(
            expires_at=timezone.now() - timezone.timedelta(hours=1)
        )
        resp = self.submit()
        self.assertEqual(resp.status_code, 201)

    def test_session_older_than_grace_rejected(self):
        StudentSession.objects.filter(pk=self.session.pk).update(
            created_at=timezone.now() - timezone.timedelta(hours=49)
        )
        resp = self.submit()
        self.assertEqual(resp.status_code, 410)

    def test_unknown_token_rejected(self):
        resp = self.submit(token="nope")
        self.assertEqual(resp.status_code, 401)

    def test_exercise_from_other_machine_rejected(self):
        other_exercise = self.cadeira.exercises.first()
        resp = self.submit(exercise_id=other_exercise.id)
        self.assertEqual(resp.status_code, 400)

    def test_invalid_sets_rejected(self):
        resp = self.submit(sets=[])
        self.assertEqual(resp.status_code, 400)
        resp = self.submit(sets=[{"weight_kg": -5, "reps": 10}])
        self.assertEqual(resp.status_code, 400)

    @patch("core.sync.HevyClient.create_workout")
    def test_student_without_hevy_link_marks_failed_no_call(self, mock_create):
        self.student.hevy_api_key_encrypted = ""
        self.student.save()
        resp = self.submit()
        self.assertEqual(resp.status_code, 201)
        self.assertEqual(resp.json()["status"], "failed")
        mock_create.assert_not_called()


    def test_duplicate_insert_race_returns_duplicate_not_500(self):
        """Two resends of the same client_uuid can pass the existence check
        at the same time; losing the insert race is a duplicate, not a crash.
        The real interleaving needs Postgres (see concurrency_probe.py); here
        the collision is forced so the recovery branch stays covered."""
        from django.db import IntegrityError

        cid = str(uuid.uuid4())
        winner = WorkoutLog.objects.create(
            client_uuid=cid,
            student=self.student,
            machine=self.supino,
            exercise=self.exercise,
            sets=[{"weight_kg": 20, "reps": 10}],
            status=WorkoutLog.STATUS_PUSHED,
            hevy_workout_id="HW-WINNER",
        )

        # Captured before patching, so calling it does not re-enter the mock.
        real_filter = WorkoutLog.objects.filter
        calls = {"n": 0}

        def filter_side_effect(*args, **kwargs):
            calls["n"] += 1
            if calls["n"] == 1:
                # The existence check runs before the other request commits.
                return real_filter(client_uuid=uuid.uuid4())
            return real_filter(*args, **kwargs)

        with patch("core.views.WorkoutLog.objects.filter", side_effect=filter_side_effect):
            with patch(
                "core.views.WorkoutLog.objects.create", side_effect=IntegrityError("dup")
            ):
                resp = self.submit(client_uuid=cid)

        self.assertEqual(resp.status_code, 200)
        self.assertTrue(resp.json()["duplicate"])
        self.assertEqual(resp.json()["hevy_workout_id"], "HW-WINNER")
        self.assertEqual(WorkoutLog.objects.filter(client_uuid=cid).count(), 1)
        winner.refresh_from_db()
        self.assertEqual(winner.status, WorkoutLog.STATUS_PUSHED)


class LoginHardeningTests(TestCase):
    def setUp(self):
        self.academia, self.supino, self.cadeira, self.student = make_pilot()
        self.headers = {"X-Device-Token": self.supino.device_token}
        from django.core.cache import cache

        cache.clear()  # DRF throttling is cache-backed

    def login(self, student_id="1001", pin="4321"):
        return self.client.post(
            "/api/v1/auth/login/",
            {"student_id": student_id, "pin": pin},
            content_type="application/json",
            headers=self.headers,
        )

    @override_settings(LOGIN_FAILURE_LIMIT=3)
    def test_student_locked_out_after_repeated_wrong_pins(self):
        for _ in range(3):
            self.assertEqual(self.login(pin="0000").status_code, 401)
        # Even the correct PIN is refused while the lockout window is open.
        resp = self.login()
        self.assertEqual(resp.status_code, 429)

    @override_settings(LOGIN_FAILURE_LIMIT=5)
    def test_successful_login_clears_failure_counter(self):
        for _ in range(3):
            self.login(pin="0000")
        self.assertEqual(self.login().status_code, 200)
        self.assertEqual(
            UsageEvent.objects.filter(student=self.student, event_type="login_failed").count(), 0
        )

    def test_unknown_student_id_is_not_distinguishable(self):
        """Unknown ID and wrong PIN must return the same body and both must
        run a hash comparison, so response time does not leak enrolment."""
        unknown = self.login(student_id="7777", pin="0000")
        wrong_pin = self.login(pin="0000")
        self.assertEqual(unknown.status_code, wrong_pin.status_code)
        self.assertEqual(unknown.json(), wrong_pin.json())

    def test_login_is_throttled_per_device(self):
        """A stolen device token must not allow unlimited PIN guessing."""
        from django.core.cache import cache

        from .throttling import DeviceLoginThrottle

        cache.clear()
        # DRF binds THROTTLE_RATES onto the class at import time, so
        # override_settings(REST_FRAMEWORK=...) never reaches it. Patch the
        # class. A low rate also keeps this to a handful of requests: at the
        # real 20/min a slow machine can spend longer than the throttle window
        # issuing them, the bucket resets mid-test, and nothing is throttled.
        with patch.object(DeviceLoginThrottle, "THROTTLE_RATES", {"login": "3/min"}):
            codes = [self.login(student_id="7777", pin="0000").status_code for _ in range(5)]

        self.assertEqual(codes[0], 401)  # the first attempts still get through
        self.assertIn(429, codes)

    def test_throttle_is_per_tablet_not_global(self):
        """One tablet hammering login must not lock out the other machines."""
        from django.core.cache import cache

        from .throttling import DeviceLoginThrottle

        cache.clear()
        with patch.object(DeviceLoginThrottle, "THROTTLE_RATES", {"login": "3/min"}):
            for _ in range(5):
                self.login(student_id="7777", pin="0000")

            other = self.client.post(
                "/api/v1/auth/login/",
                {"student_id": "1001", "pin": "4321"},
                content_type="application/json",
                headers={"X-Device-Token": self.cadeira.device_token},
            )
        self.assertEqual(other.status_code, 200)


class ConfigurationSafetyTests(TestCase):
    """The production guard that stops a deploy from encrypting Hevy keys
    with a key anybody could derive from the source."""

    @override_settings(DEBUG=False, HEVY_KEY_ENCRYPTION_KEY="")
    def test_missing_encryption_key_refuses_to_encrypt_in_production(self):
        from django.core.exceptions import ImproperlyConfigured

        from . import crypto

        with self.assertRaises(ImproperlyConfigured):
            crypto.encrypt("some-hevy-key")

    @override_settings(DEBUG=False, HEVY_KEY_ENCRYPTION_KEY="", SECRET_KEY="x", ALLOWED_HOSTS=["*"])
    def test_deploy_checks_flag_unsafe_production_config(self):
        from .checks import check_production_secrets

        ids = {e.id for e in check_production_secrets(None)}
        self.assertEqual(ids, {"core.E001", "core.E003"})

    def test_dev_key_is_random_not_derived_from_secret_key(self):
        """The old dev key was sha256(SECRET_KEY), i.e. reproducible by
        anyone with the repo. It must not be."""
        import base64
        import hashlib

        from . import crypto

        derivable = base64.urlsafe_b64encode(hashlib.sha256(settings.SECRET_KEY.encode()).digest())
        with override_settings(HEVY_KEY_ENCRYPTION_KEY=""):
            self.assertNotEqual(crypto._dev_key(), derivable)


class AdminPinTests(TestCase):
    """The maintenance code that lets gym staff leave kiosk mode. The tablet
    must be able to check it with no network, so only its hash travels."""

    def setUp(self):
        self.academia, self.supino, self.cadeira, self.student = make_pilot()
        self.headers = {"X-Device-Token": self.supino.device_token}

    def config(self):
        return self.client.get("/api/v1/machine/config/", headers=self.headers).json()

    def test_hash_is_sent_never_the_code(self):
        import hashlib

        self.academia.admin_pin = "482913"
        self.academia.save()
        data = self.config()["academia"]
        expected = hashlib.sha256(b"482913").hexdigest()
        self.assertEqual(data["admin_pin_hash"], expected)
        self.assertNotIn("482913", str(data))

    def test_blank_pin_disables_the_escape_hatch(self):
        self.academia.admin_pin = ""
        self.academia.save()
        self.assertEqual(self.config()["academia"]["admin_pin_hash"], "")

    def test_pin_is_scoped_to_the_academia(self):
        other = Academia.objects.create(name="Outra", slug="outra", admin_pin="999999")
        other_machine = Machine.objects.create(academia=other, number=1, name="Outra maq")
        self.academia.admin_pin = "111111"
        self.academia.save()

        mine = self.config()["academia"]["admin_pin_hash"]
        theirs = self.client.get(
            "/api/v1/machine/config/",
            headers={"X-Device-Token": other_machine.device_token},
        ).json()["academia"]["admin_pin_hash"]
        self.assertNotEqual(mine, theirs)


class PairingCodeTests(TestCase):
    """A six digit code is only safe because it is single use, short lived
    and rate limited. Each of those is tested here."""

    def setUp(self):
        self.academia, self.supino, self.cadeira, self.student = make_pilot()
        from django.core.cache import cache

        cache.clear()

    def pair(self, code):
        return self.client.post(
            "/api/v1/pair/", {"code": code}, content_type="application/json"
        )

    def test_code_returns_the_device_token(self):
        from .models import PairingCode

        pc = PairingCode.issue(self.supino)
        resp = self.pair(pc.code)
        self.assertEqual(resp.status_code, 200)
        self.assertEqual(resp.json()["device_token"], self.supino.device_token)
        self.assertEqual(resp.json()["machine"]["name"], self.supino.name)

    def test_token_from_pairing_actually_works(self):
        """End to end: the token handed out must open machine config."""
        from .models import PairingCode

        token = self.pair(PairingCode.issue(self.cadeira).code).json()["device_token"]
        resp = self.client.get(
            "/api/v1/machine/config/", headers={"X-Device-Token": token}
        )
        self.assertEqual(resp.status_code, 200)
        self.assertTrue(resp.json()["machine"]["is_multifunctional"])

    def test_code_is_single_use(self):
        from .models import PairingCode

        pc = PairingCode.issue(self.supino)
        self.assertEqual(self.pair(pc.code).status_code, 200)
        self.assertEqual(self.pair(pc.code).status_code, 404)

    def test_expired_code_rejected(self):
        from .models import PairingCode

        pc = PairingCode.issue(self.supino)
        PairingCode.objects.filter(pk=pc.pk).update(
            expires_at=timezone.now() - timezone.timedelta(seconds=1)
        )
        self.assertEqual(self.pair(pc.code).status_code, 404)

    def test_issuing_invalidates_the_previous_code(self):
        """Otherwise old codes left on a whiteboard stay usable."""
        from .models import PairingCode

        old = PairingCode.issue(self.supino)
        PairingCode.issue(self.supino)
        self.assertEqual(self.pair(old.code).status_code, 404)

    def test_unknown_and_malformed_codes_rejected(self):
        self.assertEqual(self.pair("000000").status_code, 404)
        self.assertEqual(self.pair("12345").status_code, 400)
        self.assertEqual(self.pair("abcdef").status_code, 400)

    def test_inactive_machine_cannot_be_paired(self):
        from .models import PairingCode

        pc = PairingCode.issue(self.supino)
        Machine.objects.filter(pk=self.supino.pk).update(is_active=False)
        self.assertEqual(self.pair(pc.code).status_code, 404)

    def test_guessing_is_rate_limited(self):
        """Six digits is a million combinations; the throttle is what makes
        that expensive rather than the code length."""
        from django.core.cache import cache

        from .throttling import PairingThrottle

        cache.clear()
        with patch.object(PairingThrottle, "THROTTLE_RATES", {"pair": "3/min"}):
            codes = [self.pair("111111").status_code for _ in range(5)]
        self.assertIn(429, codes)

    def test_pairing_is_recorded(self):
        from .models import PairingCode

        self.pair(PairingCode.issue(self.supino).code)
        self.assertTrue(UsageEvent.objects.filter(event_type="tablet_paired").exists())
