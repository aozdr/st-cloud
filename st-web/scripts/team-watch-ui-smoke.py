"""Playwright smoke checks for team search, watch list, notification compatibility, and focusId.

The test uses API interception so it is deterministic and can run with only the Vite
dev server. It exercises the rendered React routes at desktop and 375px widths without
adding a project dependency or changing the backend.
"""

from __future__ import annotations

import asyncio
import json
import os
from urllib.parse import parse_qs, urlparse

from playwright.async_api import async_playwright


BASE_URL = "http://127.0.0.1:5173"


def result(data: object) -> str:
    return json.dumps({"code": 200, "data": data}, ensure_ascii=False)


def page_result(records: list[dict], total: int | str | None = None, pages: int = 1) -> dict:
    count = len(records) if total is None else total
    return {
        "records": records,
        "total": str(count),
        "size": "20",
        "current": "1",
        "pages": str(pages),
    }


def file_node(node_id: str, name: str, parent_id: str = "0") -> dict:
    return {
        "id": node_id,
        "parentId": parent_id,
        "nodeType": 1,
        "name": name,
        "path": f"/{name}",
        "fileSize": "1024",
        "suffix": "docx",
        "contentType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "status": 0,
        "thumbnailPath": None,
        "createdAt": "2026-09-21T00:00:00",
        "updatedAt": "2026-09-21T00:00:00",
    }


async def main() -> None:
    target_calls = 0
    delete_calls = 0
    cursor_first_calls = 0
    notification_race = False

    async with async_playwright() as playwright:
        browser_executable = os.environ.get("PLAYWRIGHT_EXECUTABLE")
        if not browser_executable:
            for candidate in (
                r"C:\Program Files\Google\Chrome\Application\chrome.exe",
                r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
            ):
                if os.path.exists(candidate):
                    browser_executable = candidate
                    break
        launch_options = {"headless": True}
        if browser_executable:
            launch_options["executable_path"] = browser_executable
        browser = await playwright.chromium.launch(**launch_options)
        context = await browser.new_context(viewport={"width": 375, "height": 812})
        await context.add_init_script(
            "sessionStorage.setItem('accessToken', 'ui-smoke-token');"
            "localStorage.setItem('refreshToken', 'ui-smoke-refresh');"
        )
        page = await context.new_page()

        async def handle_api(route) -> None:
            nonlocal target_calls, delete_calls, cursor_first_calls, notification_race
            request = route.request
            parsed = urlparse(request.url)
            path = parsed.path
            query = parse_qs(parsed.query)

            if path.endswith("/auth/me"):
                await route.fulfill(
                    content_type="application/json",
                    body=result(
                        {
                            "userId": "user-1",
                            "username": "ui-smoke",
                            "nickname": "UI Smoke",
                            "avatar": None,
                            "tenantId": "tenant-1",
                            "permissions": [
                                "file:search",
                                "file:view",
                                "file:download",
                                "file:upload",
                                "file:delete",
                            ],
                        }
                    ),
                )
                return
            if path.endswith("/team/spaces"):
                await route.fulfill(
                    content_type="application/json",
                    body=result(
                        page_result(
                            [
                                {
                                    "id": "space-1",
                                    "spaceName": "产品团队",
                                    "description": None,
                                    "icon": "📁",
                                    "ownerId": "user-1",
                                    "ownerName": "UI Smoke",
                                    "storageUsed": "0",
                                    "storageQuota": "1000000",
                                    "memberCount": 1,
                                    "status": 1,
                                    "createdAt": "2026-09-21T00:00:00",
                                }
                            ],
                            total=1,
                        )
                    ),
                )
                return
            if path.endswith("/search/team"):
                keyword = query.get("keyword", [""])[0]
                if keyword == "cursorcase":
                    if "cursor" in query:
                        await route.fulfill(content_type="application/json", body=json.dumps({"code": 4604, "message": "SEARCH_CURSOR_EXPIRED"}))
                        return
                    cursor_first_calls += 1
                    await route.fulfill(
                        content_type="application/json",
                        body=result({
                            "records": [{
                                "fileId": "cursor-file", "fileName": "Cursor contract.docx",
                                "path": "/项目/Cursor contract.docx", "nodeType": 1,
                                "fileSize": "1024", "suffix": "docx", "highlight": "cursorcase",
                                "spaceId": "space-1", "parentId": "0",
                            }],
                            "hasMore": True, "nextCursor": "fixture-cursor",
                        }),
                    )
                    return
                if keyword == "alpha":
                    # Keep the first request open long enough for the second query to win.
                    try:
                        await asyncio.sleep(0.6)
                    except asyncio.CancelledError:
                        return
                name = "Alpha contract.docx" if keyword == "alpha" else "Beta contract.docx"
                file_id = "alpha-file" if keyword == "alpha" else "beta-file"
                await route.fulfill(
                    content_type="application/json",
                    body=result(
                        {
                            "records": [
                                {
                                    "fileId": file_id,
                                    "fileName": name,
                                    "path": f"/项目/{name}",
                                    "nodeType": 1,
                                    "fileSize": "1024",
                                    "suffix": "docx",
                                    "contentType": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                    "highlight": f"命中 {keyword}",
                                    "createdAt": "2026-09-21T00:00:00",
                                    "updatedAt": "2026-09-21T00:00:00",
                                    "spaceId": "space-1",
                                    "parentId": "0",
                                }
                            ],
                            "hasMore": False,
                            "nextCursor": None,
                        }
                    ),
                )
                return
            if path.endswith("/file-watches") and request.method == "GET":
                records = [] if delete_calls else [
                    {
                        "watchId": "watch-live",
                        "nodeId": "personal-file-1",
                        "nodeType": 1,
                        "spaceId": None,
                        "parentId": "0",
                        "name": "可访问文档.docx",
                        "path": "/可访问文档.docx",
                        "createdAt": "2026-09-21T00:00:00",
                        "available": True,
                    },
                    {
                        "watchId": "watch-gone",
                        "nodeId": "gone-file",
                        "nodeType": None,
                        "spaceId": None,
                        "parentId": None,
                        "name": None,
                        "path": None,
                        "createdAt": "2026-09-20T00:00:00",
                        "available": False,
                    },
                ]
                await route.fulfill(content_type="application/json", body=result(page_result(records, len(records))))
                return
            if path.endswith("/file-watches/gone-file") and request.method == "DELETE":
                delete_calls += 1
                # Explicitly exercise the empty DELETE response contract.
                await route.fulfill(status=204, body="")
                return
            if path.endswith("/notification") and request.method == "GET":
                if notification_race:
                    records = [
                        {"id": "notice-a", "type": "FILE_CHANGE", "title": "通知 A", "content": None,
                         "refType": "file", "refId": "file-a", "eventId": "event-a", "read": 1,
                         "createdAt": "2026-09-21T00:00:00"},
                        {"id": "notice-b", "type": "FILE_CHANGE", "title": "通知 B", "content": None,
                         "refType": "file", "refId": "file-b", "eventId": "event-b", "read": 1,
                         "createdAt": "2026-09-21T00:00:00"},
                    ]
                    await route.fulfill(content_type="application/json", body=result(page_result(records, total=2)))
                    return
                await route.fulfill(
                    content_type="application/json",
                    body=result(
                        page_result(
                            [
                                {
                                    "id": "legacy-notification",
                                    "type": "FILE_CHANGE",
                                    "title": "旧文件通知",
                                    "content": "旧通知内容",
                                    "refType": "file",
                                    "refId": "personal-file-1",
                                    "read": 0,
                                    "createdAt": "2026-09-21T00:00:00",
                                    # Historical data may carry nodeId but has no eventId.
                                    "nodeId": "personal-file-1",
                                    "eventId": None,
                                }
                            ],
                            total=1,
                        )
                    ),
                )
                return
            if path.endswith("/notification/notice-a/target"):
                await asyncio.sleep(0.6)
                await route.fulfill(content_type="application/json", body=result({
                    "available": True, "nodeId": "file-a", "parentId": "0", "spaceId": None, "nodeType": 1,
                }))
                return
            if path.endswith("/notification/notice-b/target"):
                await route.fulfill(content_type="application/json", body=result({
                    "available": True, "nodeId": "file-b", "parentId": "0", "spaceId": None, "nodeType": 1,
                }))
                return
            if "/notification/legacy-notification/target" in path:
                target_calls += 1
                await route.fulfill(content_type="application/json", body=result({"available": False}))
                return
            if path.endswith("/notification/legacy-notification/read"):
                await route.fulfill(content_type="application/json", body=result(None))
                return
            if path.endswith("/file/list"):
                await route.fulfill(
                    content_type="application/json",
                    body=result(page_result([file_node("personal-file-1", "个人文档.docx")], total=1)),
                )
                return
            if path.endswith("/file/storage"):
                await route.fulfill(content_type="application/json", body=result({"used": "0", "quota": "1000", "percentage": 0}))
                return
            if path.endswith("/favorite/ids"):
                await route.fulfill(content_type="application/json", body=result([]))
                return
            if path.endswith("/transfer/speed-limit"):
                await route.fulfill(content_type="application/json", body=result({"uploadSpeedLimit": 0, "downloadSpeedLimit": 0}))
                return

            # Unused AppLayout polling and bootstrap calls can safely return empty data.
            await route.fulfill(content_type="application/json", body=result({}))

        await page.route("**/api/**", handle_api)

        # U02/S06: a slow first team query must not overwrite the newer query.
        await page.goto(f"{BASE_URL}/search?scope=team&spaceId=space-1&keyword=alpha&_t=1")
        search_box = page.get_by_role("textbox", name="搜索团队文件")
        await search_box.wait_for()
        await search_box.fill("beta")
        await search_box.press("Enter")
        await page.get_by_text("Beta contract.docx").wait_for()
        assert await page.get_by_text("Alpha contract.docx").count() == 0
        assert await page.evaluate("document.documentElement.scrollWidth <= window.innerWidth")

        # U03: append cursor expiration keeps existing rows and offers a first-page retry.
        await page.goto(f"{BASE_URL}/search?scope=team&spaceId=space-1&keyword=cursorcase&_t=2")
        await page.get_by_text("Cursor contract.docx").wait_for()
        await page.get_by_role("button", name="加载更多").click()
        await page.get_by_role("button", name="重新搜索").wait_for()
        assert await page.get_by_text("Cursor contract.docx").count() == 1
        await page.get_by_role("button", name="重新搜索").click()
        await page.get_by_text("Cursor contract.docx").wait_for()
        assert cursor_first_calls == 2

        # W02/U01: unavailable records do not leak stale names, and an empty DELETE is successful.
        await page.goto(f"{BASE_URL}/following")
        await page.get_by_text("内容已不可用").wait_for()
        assert await page.get_by_text("gone-file").count() == 0
        await page.get_by_role("button", name="取消关注不可用内容").click()
        await page.get_by_text("还没有关注内容").wait_for()
        assert delete_calls == 1

        # S06: existing FileManager wiring consumes focusId and selects the target row.
        await page.goto(f"{BASE_URL}/files?focusId=personal-file-1")
        focused = page.locator('[data-file-id="personal-file-1"]')
        await focused.wait_for()
        assert "EEF0FF" in (await focused.get_attribute("class") or "")

        # W07: old FILE_CHANGE notifications must keep legacy behavior even if nodeId exists.
        await page.set_viewport_size({"width": 1280, "height": 900})
        await page.goto(f"{BASE_URL}/following")
        await page.get_by_role("button", name="通知").click()
        await page.get_by_text("旧文件通知").click()
        assert target_calls == 0

        # U04: the earlier target response must not override the later selection.
        notification_race = True
        await page.goto(f"{BASE_URL}/following")
        await page.get_by_role("button", name="通知").click()
        await page.get_by_role("menuitem", name="通知 A").click()
        await page.get_by_role("button", name="通知").click()
        await page.get_by_role("menuitem", name="通知 B").click()
        await page.wait_for_url("**/files?focusId=file-b")
        await page.wait_for_timeout(700)
        assert "focusId=file-b" in page.url

        await browser.close()
    print("team-watch UI smoke: PASS (stale search, cursor retry, unavailable watch, empty DELETE, focusId, legacy notification, target race)")


if __name__ == "__main__":
    asyncio.run(main())
