## Tubular Revived 0.29.2

Based on NewPipe 0.29.2 (extractor 202611).

**New**
- Import backups from PipePipe, NewPipe, Tubular and other forks: the app detects where a backup came from and converts it. Settings and data that Tubular has no use for are kept (so a later export contains them again) and can be removed with "Clear data from other apps".
- Settings-only export and import (JSON), compatible with PipePipe's.
- Automatic backups to a folder you choose (daily to monthly).
- Clear all subscriptions.
- SponsorBlock can use a caching proxy first and falls back to the official server.
- New app icon.

**From NewPipe 0.29.2**
- Chapters on the seek bar, YouTube "Podcasts" and "Courses" tabs, full subtitle names, player fixes (audio ducking, black screens, links with timestamps), many internal fixes and the Room 3 database migration.

**Also**
- A warning is shown at start-up if the app was not signed with the official release key.
- An occasional (random 30 to 90 days) reminder with a donation link.
- Update notes and announcements can now be sent to installed apps.

Backups are only converted, never deleted: a backup that cannot be used is refused before it touches your current data.
