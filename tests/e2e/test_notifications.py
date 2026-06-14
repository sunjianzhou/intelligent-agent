"""E2E: 通知轮询。"""


def test_poll_notifications(client):
    r = client.get("/api/notifications/poll")
    assert r.status_code == 200
    data = r.json()
    assert "notifications" in data
    assert "count" in data
    assert isinstance(data["notifications"], list)
    assert data["count"] == len(data["notifications"])
