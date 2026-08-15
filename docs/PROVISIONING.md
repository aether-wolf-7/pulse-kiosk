# Provisioning a tablet as a Pulse Kiosk

How to turn a retail Samsung Galaxy Tab A9+ into a locked kiosk terminal, and
how to get back out again. Follow it in order; step 2 is the one that fails if
you skip ahead.

You need: the tablet, a USB-C cable, a laptop with `adb`, the release APK, and
the machine's **device token** from the admin panel.

---

## 0. Before you start: the two rules

**The tablet must have zero accounts on it.** `dpm set-device-owner` refuses to
run if the tablet has any account at all, including the Samsung account that
One UI adds during its own setup wizard. There is no flag to override this.

**Never lock away your own way back in.** The app deliberately does *not*
disable ADB, safe mode, or factory reset. If a future build ever crashes on
startup while the app is the launcher, those are the only three ways to rescue
the tablet. Do not add them "for security" later.

---

## 1. Factory reset

Assume a retail tablet needs this, because One UI's setup wizard usually
planted a Samsung account before you got it.

> Settings > General management > Reset > Factory data reset

## 2. Walk the setup wizard with no network

This is the important part. At the first Wi-Fi screen choose **Skip**, then
confirm **Skip anyway**.

With no network, One UI silently drops the Google sign-in, "Copy apps & data"
and Samsung account pages, which is the only reliable way to reach a home
screen with no accounts on the device. Do not connect Wi-Fi until step 5.

Then decline every optional offer (Samsung account, Bixby, backups).

**Do not set a screen lock (PIN, pattern, password).** Leave it on swipe. A
secure lock screen makes `setKeyguardDisabled` fail, and the tablet will show
a lock screen that students cannot get past.

## 3. Enable USB debugging

1. Settings > About tablet > Software information > tap **Build number** seven times
2. Settings > Developer options > **USB debugging** on
3. Plug into the laptop, and on the tablet's RSA prompt tick
   **Always allow from this computer** > Allow

Confirm the laptop can see it:

```
adb devices
```

## 4. Verify the tablet is clean, then enrol

Both of these must come back empty / single-user. If not, go back to step 1.

```bash
adb shell dumpsys account | grep "Account {"    # must print nothing
adb shell pm list users                          # must show only UserInfo{0:...}
```

Secure Folder, Guest mode and Dual Messenger all create extra users, and any of
them blocks enrolment.

Install the **release** APK (the one you will actually run; a build deployed
straight from Android Studio is marked test-only and needs `adb install -t`):

```bash
adb install -r app-release.apk
adb shell dpm set-device-owner br.com.pulsefitness.kiosk/.kiosk.KioskDeviceAdminReceiver
```

The doubled `.kiosk.` is correct: the leading dot expands to the application
id, so the component is `br.com.pulsefitness.kiosk` + `.kiosk.KioskDeviceAdminReceiver`.
Dropping it gives "Admin component does not exist".

Expected output:

```
Success: Device owner set to package br.com.pulsefitness.kiosk
```

| Error | Cause | Fix |
|---|---|---|
| `Not allowed to set the device owner because there are already some accounts` | an account exists | remove it, or factory reset |
| `...already several users on the device` | Secure Folder / Guest / Dual Messenger | remove the extra user |
| `Admin component does not exist` | wrong component string | use the exact command above |
| `Attempt to remove non-test admin` (on removal) | release build, by design | use the in-app maintenance screen |

> **The test-only flag is captured when Device Owner is set, not when it is
> removed.** Android records whether the admin was test-only at enrolment
> time, so `dpm remove-active-admin` refuses a tablet that was enrolled with a
> non-testOnly build even if you later install a testOnly one. Verified the
> hard way on the test tablet. Practical consequence: enrol dev tablets with a
> debug build from the start, and on production tablets rely on the in-app
> break-glass, never on ADB.

> **`pm clear` does not work on a Device Owner app either** (`SecurityException:
> does not have permission CLEAR_APP_USER_DATA`). To point a tablet at a
> different machine use "Trocar este tablet de máquina" in the maintenance
> screen.

## 5. First launch and pairing

Now connect Wi-Fi (Settings, or via the tablet's own screen after launch).

```bash
adb shell am start -n br.com.pulsefitness.kiosk/.MainActivity
```

On first launch the app asks for the **device token**. Paste the token for the
machine this tablet belongs to (admin panel > Máquinas, or the output of
`manage.py seed_pilot`). The app validates it against the backend and binds the
tablet to that machine permanently.

The policy (lock task, HOME role, status bar off, keyguard off, stay awake)
applies on that first launch. Until the app has run once, the HOME role is not
yet registered.

## 6. Exempt the app from Samsung's power management

One UI will otherwise put the app to sleep after a few idle days and the
offline sync queue quietly stops draining. Do all three:

```bash
adb shell dumpsys deviceidle whitelist +br.com.pulsefitness.kiosk
```

On the tablet:
- Settings > Apps > Pulse Kiosk > Battery > **Unrestricted**
- Settings > Battery > Background usage limits > **Never sleeping apps** > add Pulse Kiosk
- Settings > Battery > Background usage limits > **Put unused apps to sleep** > off

## 7. Verify the lockdown

Reboot the tablet and check all of it:

```bash
adb reboot
```

- [ ] Comes back into the app by itself, with no tap
- [ ] Home button does nothing
- [ ] Recents button does nothing
- [ ] Swiping from the top does not open the status bar or notifications
- [ ] Long-pressing power does not offer Power off / Restart
- [ ] Screen never sleeps while plugged in
- [ ] The app says the machine's name, and login works
- [ ] Pull the Wi-Fi: the app still boots into the login screen (cached config)

Verify the lock is the real one, not the escapable screen-pinning fallback:

```bash
adb shell dumpsys activity activities | grep -i lockTask
```

## 8. Physical install

Mount the tablet so the **power and volume buttons are covered** by the
enclosure. Holding Power + Volume Up reaches Samsung's recovery menu, which can
wipe the tablet, and no software setting prevents that. The enclosure is the
only defence.

Charger permanently connected.

---

## Maintenance: getting back out

**Hidden gesture:** tap the **top-left corner** of the screen **seven times**
quickly, then enter the gym's maintenance code (admin panel > Academia >
"Código de manutenção").

The code is checked on the tablet itself, so this works with the network down,
which is the situation you will usually be in when you need it.

From the maintenance screen:

- **Liberar tablet** unpins the app and gives back the status bar, Home button
  and lock screen, so you can reach Settings, change Wi-Fi, or install an update.
- **Travar de novo** puts it back into kiosk mode. Do this before handing the
  tablet back to students.
- **Remover modo quiosque** un-enrols Device Owner completely and returns the
  tablet to a normal device. Only for retiring a tablet: putting it back into
  kiosk mode afterwards means a factory reset and starting again from step 1.

### Updating the app

USB debugging must stay enabled for this. With the tablet plugged in:

```bash
adb install -r -d app-release.apk
```

This works while the app is Device Owner and pinned. The app is killed and
relaunched as the launcher.

### If a tablet gets stuck

In order of preference:

1. Maintenance gesture > Liberar tablet.
2. `adb install -r -d` a fixed build.
3. Safe mode: power off, then hold **Volume Down** while it boots. Third-party
   apps are disabled, so One UI Home comes back and you can uninstall or fix.
4. Factory reset (Settings, or Power + Volume Up recovery menu) and start again
   from step 1.

Steps 2, 3 and 4 exist only because the app does not disable ADB, safe mode, or
factory reset. Keep it that way.
