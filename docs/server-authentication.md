# Server authentication

Phase 6A creates one owner through the interactive CLI. The database can hold
additional accounts and the integration tests create isolated owners to exercise
authorization. Passwords use versioned salted PBKDF2-HMAC-SHA256 with 600,000
iterations. Browser and Android tokens are generated with SecureRandom and only
their SHA-256 hashes are stored. Browser tokens expire after 12 hours and Android
tokens after 1 hour. Logout revokes the server-side session.

Browser login requires an exact match to the configured HTTPS Origin. Browser
mutations additionally require the matching session CSRF token. The browser
session cookie uses the __Host- prefix, Secure, HttpOnly, SameSite=Strict and
Path=/. Android uses a bearer token. The API derives dataset and epoch from
the session for each protected request and checks submitted scope against it.
No caller-supplied scope authorizes access.

Login limits are five attempts per normalized username and 30 total per
process per minute. Failed credentials receive a uniform response. The limiter
is bounded and process-local. It resets on server restart and is unsuitable as
the only production abuse control. Phase 6E must add proxy-level limits,
HTTPS validation and an exposure review before enabling Funnel. Errors return a
request ID and safe message without SQL exceptions or request bodies.

- [OWASP password storage guidance](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
