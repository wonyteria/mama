# 0.8 alarm UI and own reminder lifecycle implementation

Status: UI/reminder lane source changes prepared. Gradle, lint, unit tests, render tests, packaging, manifest registration, and phone validation are intentionally left to the owning lanes.

## Changed files

- `app/src/main/java/kr/mom/probe/reminder/AlarmContent.kt`
  - Added a shared Compose alarm surface for briefing and single task reminders.
  - Keeps stop and 10-minute snooze in a fixed footer so they remain reachable on narrow screens and large font settings.
  - Shows scheduled time as the primary visual anchor and keeps details behind a secondary action.
  - Redacts title, action text, summary, details, status text, TTS, and completion affordance when `locked=true`, even if a caller passes private content by mistake.
  - Uses higher-contrast existing colors for the stop button and small labels on the clay background.

- `app/src/main/java/kr/mom/probe/reminder/BriefingActivity.kt`
  - Replaced the old briefing layout with `AlarmContent`.
  - Preserves the existing consent/load guards, refreshes lock state on resume/focus, rechecks keyguard before details/TTS, marks only the collapsed visible notice automatically, and does not consume hidden notice rows from expansion or TTS queueing.
  - Keeps briefing completion absent; details expand in place and no longer navigate away or mark hidden rows from the button click alone.
  - Stops normal briefing notifications only through an active occurrence/generation match; demo preview still cancels only `1710 + slot`.

- `app/src/main/java/kr/mom/probe/reminder/BriefingReminders.kt`
  - Carries the exact briefing notification ID and scheduled display time into `BriefingActivity`.
  - Keeps normal briefing notification IDs at `710 + slot` and demo IDs at `1710 + slot`.
  - Adds slot-level scheduled and active occurrence state for briefing fire, stop, and snooze actions.
  - Persists snoozed briefing fire time separately from the regular slot, restores valid snooze and regular pending alarms independently, keeps the same-chain 3 snooze / 60 minute cap, and returns stale/disabled/limit/failure status instead of silently finishing.
  - Consumes the expected scheduled occurrence before posting so stale or duplicate FIRE/SNOOZED_FIRE broadcasts cannot overwrite the current alarm state.

- `app/src/main/java/kr/mom/probe/reminder/TaskAlarmActivity.kt`
  - Added the non-exported task alarm activity source. Manifest registration is still owned by the schedule lane.
  - Shows generic locked content, supports locked snooze, and reloads/revalidates task state after unlock.
  - Completion and details require current keyguard-unlocked state and active occurrence validation.
  - `onStop` stops this occurrence's notification/TTS without marking the task complete.
  - After snooze success, the hero time switches to the next reservation and labels it as “다음 알림”.

- `app/src/main/java/kr/mom/probe/task/AssistantTaskStore.kt`
  - Appended backward-compatible encrypted JSON fields for scheduled occurrence ID/time, active alarm occurrence ID/time/notification ID, and user snooze counters.
  - Added pure occurrence transitions for consume, snooze, and complete.
  - Keeps auto retry attempts separate from user snooze counters.
  - Caps user snooze at 3 times and 60 total minutes per reminder chain.
  - Rolls back task snooze state if Android alarm scheduling or scheduler-registry commit fails after the encrypted save, keeping the original active occurrence retryable.
  - Clears active/scheduled alarm state when a task is completed, deleted, or suspended, and avoids reviving suspended automatic-source tasks during later source updates.

- `app/src/main/java/kr/mom/probe/task/TaskReminderScheduler.kt`
  - Routes fired task notifications to `TaskAlarmActivity`.
  - Adds occurrence-token extras to fire, stop, and snooze intents.
  - Uses task-owned notification IDs derived from task ID plus occurrence token, separate from briefing/demo IDs.
  - Validates stale stop/snooze/fire paths before mutating store state or canceling an active notification.
  - Uses the existing Momo alarm-mode opt-in and permission check for task full-screen alarm notifications, with a 30-second timeout and `FLAG_INSISTENT` only on that explicit alarm path; normal task reminders stay regular notifications.

- `app/src/test/java/kr/mom/probe/task/AssistantTaskStoreTest.kt`
  - Added pure tests for stale occurrence rejection, snooze persistence, snooze limits, auto retry separation, and single-task completion.

- `app/src/test/java/kr/mom/probe/reminder/BriefingRemindersTest.kt`
  - Added coverage for collapsed visible seen-record marking, stale briefing snooze rejection, scheduled occurrence consumption, snoozed-fire chain preservation, persisted snoozed fire time, and briefing snooze limits.

- `app/src/test/java/kr/mom/probe/reminder/AlarmContentRenderTest.kt`
  - Added Robolectric Compose render coverage for unlocked task alarm, locked redaction, briefing-without-complete, demo preview, stale state, and 320dp plus 2x text primary control reachability.

## Coordination checkpoint

At final readback, the schedule lane had registered:

```xml
<activity android:name=".reminder.TaskAlarmActivity" android:exported="false" />
```

No manifest edit was made in this lane.

## Untested gaps

- Gradle build, lint, and test execution were not run here because the schedule lane owns the first build after all source lanes are ready.
- Robolectric screenshots were not generated in this lane; root owns visual QA from render artifacts.
- No phone, lockscreen, notification-channel, exact-alarm, full-screen intent, or real delivery behavior was validated from this PC source pass.
- No external calendar, external alarm app, network, account, or dependency behavior was changed or tested in this lane.
