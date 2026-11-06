# Port PipePipe's channel blocks and filters to Tubular Revived (branch `next`)

Status: research done 2026-11-06, no code yet. Licence: PipePipe is GPL-3.0 like Tubular, so a direct
port is allowed; keep the copyright headers and attribute PipePipe in the file comments.

## What PipePipe does
- Settings keep three **block lists** (string sets) and two switches:
  keyword list (plain words or `/regex/`), channel-name list, channel-id list, "hide shorts",
  "hide paid/members-only content". A `filter_type` multi-choice says *where* filters apply.
  Comments have a second set: keyword, user name, user id, minimum likes.
- Filtering happens **inside PipePipe's extractor**: `InfoItemsCollector.applyBlocking(FilterConfig)`
  removes items from every result list the extractor builds (search, channel, related, trending,
  playlists). `ServiceHelper.initServices()` pushes the saved lists into the extractor whenever a list
  is edited. A counter of hidden items is kept (`getFilteredCount`).
- Matching rules: shorts flag; members-only flag; keyword matcher on the item title (case-insensitive
  substring, `/regex/` with find(), invalid regex skipped); channel id as `serviceId:uploaderId`;
  exact channel name for streams (uploader name), channels (item name) and playlists (uploader name).
- UI: `FilterSettingsFragment` (the settings page), `BaseFilterListActivity` + one small subclass per
  list (add dialog with regex validation, search, swipe/click/long-press to delete), "block channel"
  entries in the stream long-press menu and on the channel page.

## What Tubular's extractor offers (checked)
- `StreamInfoItem`: uploader name, uploader **URL**, `isShortFormContent()` - yes.
- Uploader **id** and a **members-only flag** (`requiresMembership`) - **no**.
- No collector hook, so the filter must live in the app.

## Design for Tubular
1. `KeywordMatcher` - copy as is (self-contained, ~80 lines, no extractor dependency) into the app util
   package; unit tests for substring, regex, invalid regex, empty.
2. `ContentFilter` (new, in the app): holds the matcher, a set of blocked channel URLs (normalised),
   a set of blocked channel names, `hideShorts`. `apply(List<InfoItem>)` mirrors `applyBlocking`
   and returns the hidden count. Channel identity uses the **uploader URL** (for YouTube it contains
   the channel id) instead of `serviceId:id`; name matching stays as an exact-match option.
3. Chokepoints: `InfoListAdapter.addInfoItemList` (covers search, channel tabs, related items,
   kiosks, remote playlists - the same lists PipePipe filters), plus the feed (loaded from the
   database, so filter in the feed view model) and subscription notifications.
4. Settings: use PipePipe's **preference keys** (`filter_by_keyword_key`, `filter_by_channel_key`,
   `filter_by_channel_id_key` with the `_set` suffix, `filter_shorts_key`) so settings-only
   exports and PipePipe backups carry the lists over unchanged. Channel-id entries from PipePipe are
   kept but unused until ids exist (shown as "unsupported" in the list).
5. UI: settings page + list screens ported from PipePipe (layouts `activity_filter_list`,
   `dialog_add_filter`, `item_filter`, `filter_list_footer`, menu), "Block channel" in the stream menu
   (`StreamDialogDefaultEntry`) and on the channel page, hidden-items counter on list screens.
6. Backup: the lists are ordinary preferences, so they are already in every export.

## Things to fix when this lands
- `ForeignKeys.PIPEPIPE` (settings/export) currently lists the filter keys as "foreign" - remove them
  (and `filter_type_key`) from `util/` generator input and the set, otherwise "Clear data from other
  apps" would delete the user's block lists. Add a unit test that fails if a key used by the app is in
  that set.
- Import from PipePipe then maps the lists instead of parking them.

## Not portable yet (needs extractor work, separate step)
- "Hide paid / members-only content": needs a members flag in `StreamInfoItem` and the YouTube parser.
- Channel ids: needs `getUploaderId()` on stream/channel/playlist items.
- Comment filters (keyword, user, user id, min likes): comments come from a different list path;
  do after the core lists work, filtering in the comments adapter.

## Milestones
- M1: KeywordMatcher + ContentFilter + settings page + list screens + adapter hook + tests (keyword,
  channel URL/name, shorts).
- M2: stream-menu and channel-page "Block channel", feed and notification filtering, counter.
- M3: comment filters.
- M4: extractor additions (members flag, uploader id) and the matching filter options.

## Test plan
Unit: matcher, filter on fake `InfoItem` lists, preference round trip. Manual: block a channel from a
search result, see it vanish from search, related, feed; export settings, wipe, import, lists back;
import a PipePipe backup with lists and confirm they are used.
