import { test, beforeEach } from "node:test";
import assert from "node:assert/strict";
import { createApp } from "../app.js";
import * as store from "../store.js";

let server;
let base;

async function start() {
  server = createApp().listen(0);
  await new Promise((resolve) => server.once("listening", resolve));
  base = `http://127.0.0.1:${server.address().port}`;
}

async function stop() {
  await new Promise((resolve) => server.close(resolve));
}

beforeEach(() => {
  store.reset();
});

test("health endpoint reports ok", async () => {
  await start();
  try {
    const res = await fetch(`${base}/api/health`);
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.status, "ok");
  } finally {
    await stop();
  }
});

test("lists seeded tasks", async () => {
  await start();
  try {
    const res = await fetch(`${base}/api/tasks`);
    const tasks = await res.json();
    assert.equal(tasks.length, 3);
    assert.ok(tasks.some((t) => t.done));
  } finally {
    await stop();
  }
});

test("creates, toggles, and deletes a task", async () => {
  await start();
  try {
    const created = await (
      await fetch(`${base}/api/tasks`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title: "Write tests" }),
      })
    ).json();
    assert.equal(created.title, "Write tests");
    assert.equal(created.done, false);

    const toggled = await (
      await fetch(`${base}/api/tasks/${created.id}/toggle`, { method: "POST" })
    ).json();
    assert.equal(toggled.done, true);

    const del = await fetch(`${base}/api/tasks/${created.id}`, { method: "DELETE" });
    assert.equal(del.status, 204);

    const remaining = await (await fetch(`${base}/api/tasks`)).json();
    assert.ok(!remaining.some((t) => t.id === created.id));
  } finally {
    await stop();
  }
});

test("rejects empty titles", async () => {
  await start();
  try {
    const res = await fetch(`${base}/api/tasks`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ title: "   " }),
    });
    assert.equal(res.status, 400);
  } finally {
    await stop();
  }
});
