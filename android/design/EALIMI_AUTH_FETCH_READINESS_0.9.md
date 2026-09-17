# e알리미 authenticated fetch readiness

Status: connector transport and parser gate are implemented; authenticated notice selectors are still blocked on root's verified Mom WebView observation.

Implemented:
- `WebsiteSessionManager.renderAuthenticatedDom()` creates and destroys an app-owned WebView on the main dispatcher, blocks non-HTTPS/non-e알리미 navigation, disables file/content access, flushes only the app WebView cookie jar, and returns typed render outcomes.
- `EalimiNoticeClient` implements `SourceFetcher` for `ealimi-web`, refuses wrong source/kind or missing child scope, and never treats cookie presence or navigation alone as authentication.
- `EalimiDomReader` recognizes the official login DOM/URL as `AUTH_REQUIRED`, returns `UNSUPPORTED` while authenticated notice selectors are pending, and only emits records from a verified payload with matching school and grade scope plus actual list/detail reads.
- Unit fixtures cover login redirect, pending contract, verified synthetic notice payload, wrong child scope, blocked navigation, and invalid fetch scope.

Root must provide a sanitized authenticated DOM contract with these fields before e알리미 can be claimed complete:

```json
{
  "contractVersion": "ealimi-auth-dom-v1",
  "url": "https://www.ealimi.com/...",
  "authenticated": true,
  "listRead": true,
  "school": { "name": "성남정자초등학교", "level": "ELEMENTARY" },
  "child": { "grade": 2 },
  "notices": [
    {
      "id": "stable-public-safe-id",
      "title": "redacted or synthetic title preserving structure",
      "body": "redacted or synthetic body preserving structure",
      "detailUrl": "https://www.ealimi.com/...",
      "detailRead": true,
      "publishedText": "observed publication text if visible",
      "publishedAt": null,
      "attachments": [
        { "title": "filename only", "url": "https://www.ealimi.com/...", "contentType": "application/pdf" }
      ]
    }
  ]
}
```

The contract must not include cookies, password fields, hidden input values, CSRF tokens, child names, class numbers, phone numbers, or arbitrary DOM HTML. Selectors should be limited to the verified school/child scope, notice list, notice detail, publication text, and attachment link containers observed after official WebView login.
