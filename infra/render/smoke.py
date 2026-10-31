"""Exercise the disposable local Render-profile stack with real signed tokens.

Only connects to localhost ports from compose.smoke.yaml. Test credentials below
are deliberately disposable and must never be used in a hosted environment.
"""
import json
import base64
import urllib.parse
import urllib.request
import uuid

AUTH = "http://127.0.0.1:18280"
API = "http://127.0.0.1:18286/api/v1/workspace"


def call(url, method="GET", body=None, token=None, form=False):
    headers = {"X-Forwarded-Proto": "https"}
    data = None
    if body is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
        data = urllib.parse.urlencode(body).encode() if form else json.dumps(body).encode()
    if token:
        headers["Authorization"] = "Bearer " + token
    with urllib.request.urlopen(urllib.request.Request(url, data=data, headers=headers, method=method), timeout=45) as response:
        payload = response.read()
        return json.loads(payload) if payload else None


def main():
    owner = call(AUTH + "/realms/ledgerflow/protocol/openid-connect/token", "POST", {
        "grant_type": "client_credentials", "client_id": "payments-service",
        "client_secret": "local-smoke-service-only", "scope": "ledger.post",
    }, form=True)["access_token"]
    administrator = call(AUTH + "/realms/master/protocol/openid-connect/token", "POST", {
        "grant_type": "password", "client_id": "admin-cli", "username": "smoke-admin",
        "password": "local-smoke-admin-only",
    }, form=True)["access_token"]
    admin = AUTH + "/admin/realms/ledgerflow"
    browser_client = call(admin + "/clients?clientId=ledgerflow-web", token=administrator)[0]
    # Enable a password grant only inside this disposable local test realm.
    browser_client["directAccessGrantsEnabled"] = True
    call(admin + "/clients/" + browser_client["id"], "PUT", browser_client, administrator)
    suffix = uuid.uuid4().hex[:12]
    email = "client-" + suffix + "@example.test"
    username = "client-" + suffix
    call(admin + "/users", "POST", {
        "username": username, "email": email, "emailVerified": True,
        "firstName": "Smoke", "lastName": "Client", "enabled": True,
        "credentials": [{"type": "password", "value": "local-smoke-client-only", "temporary": False}],
    }, administrator)
    client = call(AUTH + "/realms/ledgerflow/protocol/openid-connect/token", "POST", {
        "grant_type": "password", "client_id": "ledgerflow-web", "username": username,
        "password": "local-smoke-client-only", "scope": "openid profile email",
    }, form=True)["access_token"]
    contact_id, proposal_id = str(uuid.uuid4()), str(uuid.uuid4())
    claims = json.loads(base64.urlsafe_b64decode(client.split('.')[1] + '=='))
    print("Client claim checks:", {"has_subject": bool(claims.get("sub")), "verified": claims.get("email_verified"), "verified_type": type(claims.get("email_verified")).__name__, "email_matches": claims.get("email") == email})
    call(API + "/clients", "POST", {"id": contact_id, "name": "Smoke Client", "company": "Smoke Studio", "email": email}, owner)
    call(API + "/proposals", "POST", {
        "id": proposal_id, "clientId": contact_id, "title": "Hosted stack verification",
        "brief": "Persistent data and authenticated proposal acceptance.", "dueDate": "2027-01-31",
        "lines": [{"title": "Discovery", "amount": "750.25"}, {"title": "Delivery", "amount": "249.75"}],
    }, owner)
    call(API + "/proposals/" + proposal_id + "/publish", "POST", {}, owner)
    portal = call(API + "/portal", token=client)
    assert any(item["id"] == proposal_id for item in portal)
    accepted = call(API + "/proposals/" + proposal_id + "/decision", "POST", {"status": "ACCEPTED"}, client)
    repeated = call(API + "/proposals/" + proposal_id + "/decision", "POST", {"status": "ACCEPTED"}, client)
    assert accepted["projectId"] == repeated["projectId"]
    assert len(accepted["tasks"]) == 2
    task = accepted["tasks"][0]
    call(API + "/tasks/" + task["id"], "PATCH", {"status": "DONE", "version": 0}, owner)
    saved = call(API + "/proposals/" + proposal_id, token=client)
    assert sum(task["status"] == "DONE" for task in saved["tasks"]) == 1
    snapshot = call(API, token=owner)
    assert any(project["id"] == accepted["projectId"] for project in snapshot["projects"])
    print("PASS: real tokens, PostgreSQL records, client portal, acceptance retry, project creation and deliverable progress")


if __name__ == "__main__":
    main()
