# AAPS
* Check the wiki: https://wiki.aaps.app
*  Everyone who's been looping with AAPS needs to fill out the form after 3 days of looping  https://docs.google.com/forms/d/14KcMjlINPMJHVt28MDRupa4sz4DDIooI4SrW0P3HSN8/viewform?c=0&w=1

[![Support Server](https://img.shields.io/discord/629952586895851530.svg?label=Discord&logo=Discord&colorB=7289da&style=for-the-badge)](https://discord.gg/4fQUWHZ4Mw)

[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/master.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/master)
[![Crowdin](https://d322cqt584bo4o.cloudfront.net/androidaps/localized.svg)](https://translations.aaps.app/project/androidaps)
[![Documentation Status](https://readthedocs.org/projects/androidaps/badge/?version=latest)](https://wiki.aaps.app/en/latest/?badge=latest)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/master/graph/badge.svg?token=EmklfIV6bH)](https://codecov.io/gh/nightscout/AndroidAPS)

DEV:
[![CircleCI](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev.svg?style=svg)](https://circleci.com/gh/nightscout/AndroidAPS/tree/dev)
[![codecov](https://codecov.io/gh/nightscout/AndroidAPS/branch/dev/graph/badge.svg?token=EmklfIV6bH)](https://codecov.io/gh/nightscout/AndroidAPS/tree/dev)

---

## This Branch: Eversense CGM (E3 / E365) + Afrezza Inhaled Insulin

This branch adds two features on top of upstream AAPS:

1. **Eversense CGM integration** — direct BLE connection to Eversense E3 and E365 transmitters as a native AAPS BG source, with calibration, alarms, DMS portal sync, and (for E365) cloud upload.
2. **Afrezza inhaled insulin support** — a second, independently-tracked insulin curve for logging Technosphere inhaled insulin doses, plus an optional post-dose max-basal safety mechanism.

Both are experimental, community-developed modifications. Neither is approved by any regulatory body. **Discuss any changes to your insulin regimen with your endocrinologist before use, and always keep fingerstick meter access as a backup.**

---

## Eversense CGM: Getting Started

### Sensor Insertion (Done by Your Doctor)
1. Visit your trained healthcare provider for the procedure. Only physicians who have completed the Eversense CGM Insertion and Removal Training Program may perform the insertion.
2. The tiny sensor is placed just under the skin of your upper arm via a small incision, closed with Steri-Strips — usually no stitches required.

### Incision Care (First Few Days)
3. Avoid strenuous activities that may pull at the incision or cause heavy sweating while it heals. Remove the Steri-Strips within a few days.

### Warm-Up Phase
4. After insertion, the official Eversense app will show "Warm Up Phase." The sensor must complete this period before calibration is accepted. Do not attempt to calibrate during this phase.
5. After warm-up, the official Eversense app will move into an **Initialization Phase**. This must also be completed using the official Eversense app before proceeding.
6. While still in the official Eversense app, set your **Low and High BG Alert levels**. These are stored on the transmitter itself, so this only needs to be done once per transmitter (or whenever you want to change them) — but it must be done through the official app; AAPS does not write these settings to the transmitter.
7. Once warm-up, initialization, and alert levels are all complete, proceed to set up the AAPS plugin.

---

### ⚠️ IMPORTANT — E365 vs E3: Official App Coexistence Differs

The Eversense transmitter can only maintain **one active Bluetooth connection at a time**. Whether the official Eversense app can safely coexist with AAPS depends on which transmitter you have.

#### E365 — the official app and AAPS cannot reliably coexist

Through real-world testing, we found that leaving the official Eversense app installed alongside AAPS on an E365 setup leads to intermittent BLE connection contention — the official app's more aggressive background reconnect behavior can "win" the race for the transmitter's single connection slot after any interruption, leaving AAPS unable to reconnect on its own until you manually intervene.

**The reliable setup for E365 is:**

1. Complete warm-up, initialization, and on-transmitter Low/High BG alert-level setup using the official Eversense app (see "Warm-Up Phase" above) — these all happen once, before you touch AAPS.
2. Once that's done, **completely delete the official Eversense app from your phone** (not just force-stop — a full uninstall). This removes the only thing AAPS was competing against for the connection.

**⚠️ Warning:** if you do not fully delete the official Eversense app, AAPS can lose the transmitter connection and fail to reconnect on its own in any of the following situations:
- The Bluetooth connection is interrupted (e.g. Bluetooth toggled off/on, moving out of range, brief RF interference such as walking through a security/metal detector).
- The phone is restarted.
- AAPS is updated (installing a new build/APK).

In each of these cases, the official app — if still installed — can grab the transmitter's connection before AAPS gets a chance to, and you will need to manually reconnect. Fully deleting the official app removes this risk entirely.

3. Now connect the E365 transmitter to AAPS (see "Transmitter & AAPS Setup" below). With no competing app, AAPS's own reconnect logic reliably recovers the connection on its own after Bluetooth interruptions, phone restarts, etc. — no manual reconnect should be needed.
4. If you ever need to change your alert levels again, or check firmware updates, you'll need to temporarily reinstall the official app, make your changes, and delete it again afterward.

#### E3 — the official app and AAPS can run together

Unlike the E365, the E3 does not exhibit the same contention problem — it can remain connected to **both** the official Eversense app and AAPS simultaneously in coexistence mode. You do not need to delete the official app for E3 use.

---

### Transmitter & AAPS Setup

8. **E365 only:** confirm you've completed warm-up, initialization, and alert-level setup above, then completely delete the official Eversense app.
9. **E3 only:** you may leave the official app installed; no disconnect step is required.
10. In AAPS, go to Config Builder and select Eversense as your BG source.
11. Open the Eversense plugin settings and enter your Eversense DMS account credentials (username and password). Both the E3 and E365 require credentials to authenticate with the Eversense cloud and retrieve your transmitter's security certificate at every new connection.
12. Tap Connect to find your transmitter and pair it via Bluetooth.

---

## Eversense Plugin Settings — Functions Explained

### Credentials
Enter your Eversense DMS account username and password. Both the E3 and E365 require credentials to authenticate with the Eversense cloud and retrieve your transmitter's security certificate at every new connection.

### Calibration
Displays your current calibration phase, the date of your last calibration, and when your next calibration is due. When the transmitter is ready, the Calibrate button becomes active — tap it, enter your fingerstick reading, and the value is sent directly to the transmitter over Bluetooth. Calibration is not accepted during the warm-up or initialization phase. An alert to calibrate will be displayed each time a calibration is required.

### Transmitter Placement Signal
Shows real-time signal strength between the transmitter and the sensor. Signal levels are:
- Excellent (>=75)
- Good (48-74)
- Low (30-47)
- Poor (25-29)
- Very Poor (1-24)

If the signal is poor for 3 or more consecutive readings, you will receive an urgent notification. Tap it to open the placement guide.

### Notifications & Alerts
The plugin provides the following system notifications:
- Transmitter not placed — urgent alert with a link to the placement guide.
- Firmware version — shown once per firmware version, prompting you to check for updates in the official Eversense app.
- Transmitter alarms — high/low glucose and other transmitter alerts are relayed as AAPS notifications.
- Calibration required — an alert to calibrate is displayed each time a calibration is required.

### DMS Portal Sync
After every glucose reading, the plugin automatically uploads your data to the Eversense DMS web portal so your care team can view it in real time. Each sync uploads your latest glucose value, trend, signal strength, and battery level; your glucose history; and device diagnostic logs.

These same uploads are also sent to the **Eversense NOW** app, available on the Play Store. Eversense NOW lets a follower see your live BG readings on their own device — useful for caregivers of people with T1D who want remote visibility into glucose levels.

---

## Daily Use

13. Apply a fresh adhesive patch each morning and wear the transmitter over the sensor site on your upper arm.
14. Charge the transmitter daily — no glucose data is collected while it is charging.
15. Check AAPS for your glucose reading, trend arrows, and alerts.
16. Monitor placement signal in the plugin settings if readings seem inconsistent.
17. Calibrate when due — tap the Calibrate button and enter your fingerstick reading.
18. **E3 only:** if you need to switch back to the official Eversense app temporarily, go to AAPS Settings → CGM and delete the CGM source, then reconnect in the official app.
19. **E365 only:** if you need to make on-transmitter changes (alert levels, firmware check), you will need to temporarily reinstall the official Eversense app, make your changes, then delete it again before reconnecting to AAPS (see the E365 note above).

---

## Afrezza Inhaled Insulin

### What This Adds

The Afrezza plugin adds support for **Technosphere inhaled insulin (Afrezza)** alongside your pump insulin. It tracks Afrezza's insulin-on-board (IOB) using the correct pharmacokinetic curve — peak at ~40 minutes, duration of ~2.5 hours — instead of applying your pump insulin's longer curve to inhaled doses. This means AAPS correctly predicts when Afrezza wears off, and your pump resumes normal basal delivery on the right schedule instead of running with phantom IOB.

An optional **post-dose max-basal safety mechanism** is also included, described in Part 3 below.

**Safety reminders:**
- Always discuss insulin changes with your endocrinologist before using Afrezza alongside your pump.
- Monitor your CGM closely, especially in your first week of use.
- Afrezza dosing is not perfectly linear across cartridge sizes — start conservative and adjust based on your own response.
- Afrezza is for meal coverage and corrections only — it does not replace your pump's basal delivery.
- This is experimental, community-developed software, not approved by any regulatory body. Use at your own risk.

### Part 1: Adding Afrezza as an Insulin Type

1. Open AAPS, navigate to **Insulin Management**.
2. Tap **+** to add a new insulin, and select **"Afrezza (Inhaled)"** from the template list.
3. The editor opens with default values: **Peak 40 minutes**, **DIA 2.5 hours**, **Concentration U100**.
4. The DIA slider allows a range of **1.5 to 4.0 hours**. The default of 2.5 hours works for most users; adjust based on your own response (e.g. 2.0h if Afrezza wears off faster for you, 3.0h if you regularly use 12U cartridges).
5. Tap **Save**. Your Insulin Management list should now show both your pump insulin (unchanged, still your active profile insulin) and "Afrezza (Inhaled)" alongside it. Afrezza does not replace your pump insulin — it exists independently for manual logging only.

**If the DIA slider shows a 5.0–10.0 hour range and won't go below 5.0**, the Afrezza template wasn't selected properly — go back and reselect "Afrezza (Inhaled)" from the template list.

### Part 2: Logging an Afrezza Dose

1. Open the Afrezza dialog (from the treatment sheet, or wherever it's placed in your build's navigation).
2. **Step 1 — Select cartridge:** tap **4U**, **8U**, or **12U** to match the cartridge you're about to inhale.
3. **Step 2 — Confirm:** a dialog asks *"Log \[X\]U Afrezza?"* — tap Confirm.
4. **Step 3 — Max basal prompt (optional):** a dialog asks *"Apply Max Basal? Set temporary basal rate to \[X.X\] U/h to counter Afrezza glucose rise?"* This is your Afrezza max-basal rate setting (see Part 3). Tap **Yes** to use it for this dose, or **No** to skip it and log the dose alone.
5. **Step 4 — Duration selector (only if you accepted Step 3):** choose how long to apply the elevated basal — **1 hour, 2 hours, or 3 hours**. As a general guide: **1 hour** for carb-heavy meals, **2 hours** for meals with an even mix of carbs, fat, and protein, and **3 hours** for fat-heavy or high-protein meals. The recommended **Afrezza max basal rate** setting (Part 3 below) is to match your current highest basal rate in your profile.
6. **Step 5 — Carb prompt:** a dialog asks *"Enter Carbs? Open the Bolus Calculator to enter carbs for this meal?"* Tap **Yes** to jump straight into the bolus wizard for this meal's carbs, or **No** to finish without logging carbs right now.
7. Inhale the Afrezza cartridge.

### Part 3: The Afrezza Max-Basal Safety Mechanism

This is an **optional, per-dose** feature — you choose whether to use it each time you log a dose (Step 3 above). It is not automatic and does not run unless you accept the prompt.

**What it does:** for a limited time after your dose (1/2/3 hours, your choice), it allows the loop to run at a higher basal rate than it would otherwise calculate — helping counter the glucose rise from a meal while Afrezza's fast-acting curve is still working. It can only ever *raise* the loop's calculated rate; it never forces basal lower than what the loop already decided.

**Where the rate comes from:** Settings → OpenAPS SMB settings → **"Afrezza max basal rate"** (range 0.1–3.0 U/h, default 2.0 U/h). This is the rate offered in the Step 3 prompt each time. **Recommended setting:** match your current highest basal rate in your profile.

**Built-in safety limits:**
- **Hard capped by your own OpenAPS Max Basal setting** — the Afrezza rate can never exceed whatever you've already configured as your absolute max basal; a higher Afrezza value simply has no additional effect.
- **Hypo / CGM-dropout guard** — if your current BG is unavailable (CGM dropout) or between 1–70 mg/dL, the mechanism pauses and will not raise basal until BG data is available and above 70.
- **Carb-aware auto-cancel** — once carbs-on-board (COB) reaches zero, the mechanism checks for an active *extended* carb entry (e.g. pizza, high-fat meals logged with a duration) — if one is still running, it continues. If not (e.g. a simple, fast-absorbing meal), it allows a 5-minute grace period after COB hits zero, then automatically cancels itself.
- **Manual cancel anytime** — while active, a card at the top of the Afrezza dialog shows the current rate and time remaining, with a **Cancel** button to stop it immediately.

### Part 4: Understanding Dual IOB Tracking

AAPS tracks two separate IOB curves at once: your **pump insulin** (its normal DIA, typically 5+ hours) covering basal, SMBs, and manual pump boluses; and **Afrezza IOB** (peak 40min, DIA per your Part 1 setting) covering only doses logged through the Afrezza flow. The total IOB shown on the home screen is the sum of both, and AAPS uses the combined value for all predictions and dosing decisions.

### Troubleshooting

- **"Add Afrezza insulin in Insulin Management first"** — you haven't completed Part 1 yet.
- **DIA slider won't go below 5.0** — the wrong template was selected; reselect "Afrezza (Inhaled)" specifically.
- **IOB seems too high after a dose** — check that your Afrezza insulin's DIA is actually set to your intended value (e.g. 2.5h), not left at a pump-insulin-length default.
- **Max basal doesn't seem to be raising basal** — check your OpenAPS Max Basal setting; if it's lower than your Afrezza max-basal rate, the OpenAPS setting wins and caps the effective rate.

---

## Known Limitations

- The Afrezza max-basal safety mechanism and the Eversense connection logic are both actively evolving. Check the branch's commit history for the latest state before relying on either in a real-world dosing decision.
- The E365/official-app contention issue is a platform-level Android Bluetooth limitation (only one app can hold an active GATT connection to the transmitter at a time), not a bug specific to either app. There is no way to make two apps share the connection simultaneously for the E365.
