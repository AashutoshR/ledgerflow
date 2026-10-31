"""Update portal OIDC scopes in an existing local realm without replacing users."""
import argparse
import getpass
import json
import os
from pathlib import Path
import urllib.error
import urllib.parse
import urllib.request


def configure(base_url, realm_name, username, password):
    token_body = urllib.parse.urlencode({"grant_type": "password", "client_id": "admin-cli", "username": username, "password": password}).encode()
    request = urllib.request.Request(base_url + "/realms/master/protocol/openid-connect/token", data=token_body, headers={"Content-Type": "application/x-www-form-urlencoded"})
    with urllib.request.urlopen(request, timeout=20) as response:
        token = json.load(response)["access_token"]
    admin = base_url + "/admin/realms/" + urllib.parse.quote(realm_name, safe="")

    def call(method, path, body=None):
        headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
        request = urllib.request.Request(admin + path, data=json.dumps(body).encode() if body is not None else None, headers=headers, method=method)
        with urllib.request.urlopen(request, timeout=20) as response:
            content = response.read()
            return json.loads(content) if content else None

    desired = json.loads(Path(__file__).with_name("realm.json").read_text(encoding="utf-8"))
    clients = call("GET", "/clients?clientId=ledgerflow-web")
    if len(clients) != 1:
        raise RuntimeError("Expected one existing ledgerflow-web client")
    for scope in desired["clientScopes"]:
        if scope["name"] not in ("basic", "email", "profile"):
            continue
        scopes = call("GET", "/client-scopes")
        existing = next((item for item in scopes if item["name"] == scope["name"]), None)
        if existing is None:
            call("POST", "/client-scopes", scope)
            existing = next(item for item in call("GET", "/client-scopes") if item["name"] == scope["name"])
        scope_path = "/client-scopes/" + existing["id"]
        # Preserve other existing mappers; update only the portal's named claims.
        current = call("GET", scope_path + "/protocol-mappers/models")
        for mapper in scope["protocolMappers"]:
            found = next((item for item in current if item["name"] == mapper["name"]), None)
            if found:
                call("PUT", scope_path + "/protocol-mappers/models/" + found["id"], {**mapper, "id": found["id"]})
            else:
                call("POST", scope_path + "/protocol-mappers/models", mapper)
        call("PUT", "/clients/" + clients[0]["id"] + "/default-client-scopes/" + existing["id"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://localhost:8180")
    parser.add_argument("--realm", default="ledgerflow")
    parser.add_argument("--username", default="admin")
    args = parser.parse_args()
    password = os.environ.get("KEYCLOAK_ADMIN_PASSWORD") or getpass.getpass("Local Keycloak administrator password: ")
    try:
        configure(args.base_url.rstrip("/"), args.realm, args.username, password)
    except urllib.error.HTTPError as error:
        raise SystemExit("Keycloak configuration failed (HTTP " + str(error.code) + "). Check administrator credentials and realm access.") from None
    except (urllib.error.URLError, RuntimeError) as error:
        raise SystemExit("Keycloak configuration failed: " + str(error)) from None
    print("Portal email/profile scopes configured. Sign out and sign in again to refresh claims.")


if __name__ == "__main__":
    main()
