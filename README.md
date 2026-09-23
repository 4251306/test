# Task Board

A minimal but complete full-stack demo app used to exercise the Cloud Agent development
environment end to end. It is an Express server with an in-memory REST API and a modern
vanilla-JavaScript single-page UI.

## Requirements

- Node.js >= 20 (the Cloud Agent default image ships Node 22)

## Getting started

```bash
npm install      # install dependencies
npm run dev      # start the dev server with auto-reload on http://localhost:3000
npm start        # start the server without watch mode
npm test         # run the test suite (node:test)
```

Then open http://localhost:3000 to use the Task Board: add tasks, toggle them complete,
and delete them.

## REST API

| Method | Path                    | Description                     |
| ------ | ----------------------- | ------------------------------- |
| GET    | `/api/health`           | Health check                    |
| GET    | `/api/tasks`            | List all tasks                  |
| POST   | `/api/tasks`            | Create a task `{ "title" }`     |
| POST   | `/api/tasks/:id/toggle` | Toggle a task's done state      |
| DELETE | `/api/tasks/:id`        | Delete a task                   |

## Project layout

```
app.js                  Express app + routes
server.js               HTTP entrypoint
store.js                In-memory task store (unit-testable)
public/                 Static single-page UI (HTML/CSS/JS)
test/api.test.js        API tests (node:test)
.cursor/environment.json  Cloud Agent environment config
```

## Cloud Agent environment

`.cursor/environment.json` configures the environment: `npm install` on setup, a
persistent `dev-server` terminal running `npm run dev`, and port `3000` exposed.
