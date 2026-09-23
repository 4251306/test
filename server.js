import { createApp } from "./app.js";

const port = process.env.PORT || 3000;
const host = process.env.HOST || "0.0.0.0";

createApp().listen(port, host, () => {
  console.log(`Task Board running at http://${host}:${port}`);
});
