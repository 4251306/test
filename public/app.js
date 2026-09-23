const listEl = document.getElementById("task-list");
const emptyEl = document.getElementById("empty");
const statusEl = document.getElementById("status");
const formEl = document.getElementById("new-task");
const inputEl = document.getElementById("task-title");

async function api(path, options) {
  const res = await fetch(path, {
    headers: { "Content-Type": "application/json" },
    ...options,
  });
  if (!res.ok && res.status !== 204) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || `Request failed (${res.status})`);
  }
  return res.status === 204 ? null : res.json();
}

function render(tasks) {
  listEl.innerHTML = "";
  emptyEl.hidden = tasks.length > 0;

  for (const task of tasks) {
    const li = document.createElement("li");
    li.className = `task${task.done ? " task--done" : ""}`;

    const check = document.createElement("input");
    check.type = "checkbox";
    check.className = "task__check";
    check.checked = task.done;
    check.addEventListener("change", () => toggleTask(task.id));

    const title = document.createElement("span");
    title.className = "task__title";
    title.textContent = task.title;

    const del = document.createElement("button");
    del.className = "task__delete";
    del.textContent = "×";
    del.title = "Delete task";
    del.addEventListener("click", () => deleteTask(task.id));

    li.append(check, title, del);
    listEl.append(li);
  }

  const remaining = tasks.filter((t) => !t.done).length;
  statusEl.textContent = `${tasks.length} task(s) · ${remaining} remaining`;
}

async function refresh() {
  render(await api("/api/tasks"));
}

async function addTask(title) {
  await api("/api/tasks", { method: "POST", body: JSON.stringify({ title }) });
  await refresh();
}

async function toggleTask(id) {
  await api(`/api/tasks/${id}/toggle`, { method: "POST" });
  await refresh();
}

async function deleteTask(id) {
  await api(`/api/tasks/${id}`, { method: "DELETE" });
  await refresh();
}

formEl.addEventListener("submit", async (event) => {
  event.preventDefault();
  const title = inputEl.value.trim();
  if (!title) return;
  inputEl.value = "";
  try {
    await addTask(title);
  } catch (err) {
    statusEl.textContent = err.message;
  }
});

refresh().catch((err) => {
  statusEl.textContent = err.message;
});
