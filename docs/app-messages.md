# Update check, messages and donation links

The app fetches `https://feuerswut.de/res/txt/tubular_version.json` (at most once per `Expires`
period, or when the user checks manually). Everything below is optional except `version`/`url`.

```json
{
  "version": "0.29.2",
  "url": "https://github.com/feuerswut/Tubular-Revived/releases/latest",
  "message": "Shown in the update notification when a newer version exists.",

  "messages": [
    {
      "id": "2026-11-proxy-maintenance",
      "title": "Short title",
      "text": "Notification text, up to 1000 characters.",
      "url": "https://example.org/more-info",
      "minVersion": "0.29.0",
      "maxVersion": "0.29.2",
      "expires": "2026-12-31T00:00:00Z"
    }
  ],

  "donations": [
    { "label": "Ko-fi", "url": "https://ko-fi.com/..." },
    { "label": "GitHub Sponsors", "url": "https://github.com/sponsors/..." }
  ]
}
```

## `messages`
- Shown as a notification (update channel) the next time an app checks, **independent of whether
  an update exists**. Each `id` is shown once per installation; use a new id for a new message.
- Required: `id` (max 100 chars), `title`, `text`. A message missing one of them is ignored.
- `url`: optional, **https only** (anything else is dropped). Opened when the notification is tapped.
- `minVersion` / `maxVersion`: optional, inclusive app version range (`0.29.2`).
- `expires`: optional ISO-8601 instant. An unparsable value drops the message (so a typo can never
  make it live forever).
- Users with notifications disabled get the message when they enable them later.
- Remove the message from the file when it is no longer relevant; apps remember seen ids
  (last 200).

## `donations`
- Used by the donation reminder (random 30 to 90 days apart). Each entry needs a `label` and an https `url`.
- One entry opens directly, several entries are shown as a list.
- If the key is missing or empty, the app falls back to the built-in `tubular_donation_url`
  (`app/src/main/res/values/donottranslate.xml`). If that is empty too, no reminder is shown.
- The first reminder comes a random 30 to 90 days after first launch. After it ("Later", or a
  donate tap) the next one is again a random 30 to 90 days away; "Don't ask again" stops them.

## SponsorBlock proxy
The app asks `https://feuerswut.de/~/plugins/sponsorblock/skipSegments/<hash prefix>?...` first (setting
"Caching proxy URL", empty disables it) and falls back to the official API on any non-200
answer. Server side: the `hfs-sponsorblock-proxy` HFS plugin.
