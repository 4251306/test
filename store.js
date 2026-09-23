// Tiny in-memory task store. Kept separate from the HTTP layer so it can be
// unit-tested without starting a server.

let nextId = 1;
const tasks = new Map();

function seed() {
  tasks.clear();
  nextId = 1;
  create({ title: "Explore the codebase" });
  create({ title: "Set up the Cloud Agent environment" });
  const done = create({ title: "Verify the app runs end to end" });
  toggle(done.id);
}

export function list() {
  return Array.from(tasks.values()).sort((a, b) => a.id - b.id);
}

export function create({ title }) {
  const clean = String(title ?? "").trim();
  if (!clean) {
    const err = new Error("title is required");
    err.status = 400;
    throw err;
  }
  const task = { id: nextId++, title: clean, done: false, createdAt: Date.now() };
  tasks.set(task.id, task);
  return task;
}

export function toggle(id) {
  const task = tasks.get(Number(id));
  if (!task) {
    const err = new Error("task not found");
    err.status = 404;
    throw err;
  }
  task.done = !task.done;
  return task;
}

export function remove(id) {
  if (!tasks.delete(Number(id))) {
    const err = new Error("task not found");
    err.status = 404;
    throw err;
  }
}

export function reset() {
  seed();
}

seed();
