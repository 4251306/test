const strips = document.querySelector("#strips");
const master = document.querySelector("#master");
const masterRead = document.querySelector("#master-read");
const link = document.querySelector("#link");

function el(tag, className) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  return node;
}

function strip(index) {
  const root = el("section", "strip");
  const name = el("input", "name");
  name.value = "Ch " + (index + 1);
  name.maxLength = 24;
  name.addEventListener("change", () => send(index, { name: name.value }));

  const meter = el("div", "meter");
  const bar = document.createElement("i");
  meter.appendChild(bar);

  const fader = el("input", "fader");
  fader.type = "range";
  fader.min = "0";
  fader.max = "100";
  fader.value = "0";
  const read = el("div", "read");
  read.textContent = "0";
  fader.addEventListener("input", () => {
    read.textContent = fader.value;
    send(index, { vol: Number(fader.value) * 10 });
  });

  const pan = el("input", "pan");
  pan.type = "range";
  pan.min = "-100";
  pan.max = "100";
  pan.value = "0";
  pan.addEventListener("input", () => send(index, { pan: Number(pan.value) }));

  const buttons = el("div", "buttons");
  const mute = button("M", "mute", () => {
    mute.classList.toggle("on");
    post("/api/channel", { channel: index + 1, mute: mute.classList.contains("on") });
  });
  const solo = button("S", "solo", () => {
    solo.classList.toggle("on");
    post("/api/channel", { channel: index + 1, solo: solo.classList.contains("on") });
  });
  buttons.append(mute, solo);

  root.append(name, meter, fader, read, pan, buttons);
  return { root, name, bar, fader, read, pan, mute, solo, hold: false };
}

function button(label, className, onClick) {
  const node = el("button", className);
  node.type = "button";
  node.textContent = label;
  node.addEventListener("click", onClick);
  return node;
}

const views = [];
for (let i = 0; i < 16; i += 1) {
  const view = strip(i);
  views.push(view);
  strips.appendChild(view.root);
}

const timers = new Map();
const pending = new Map();
function send(index, fields) {
  pending.set(index, Object.assign(pending.get(index) || {}, fields));
  clearTimeout(timers.get(index));
  timers.set(index, setTimeout(() => {
    const body = pending.get(index) || {};
    pending.delete(index);
    post("/api/channel", Object.assign({ channel: index + 1 }, body));
  }, 40));
}

async function post(url, body) {
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
  if (!response.ok) return;
  apply(await response.json(), false);
}

function apply(state, force) {
  state.channels.forEach((channel, index) => {
    const view = views[index];
    if (document.activeElement !== view.name) view.name.value = channel.name;
    if (force || document.activeElement !== view.fader) {
      view.fader.value = String(Math.round(channel.vol / 10));
      view.read.textContent = view.fader.value;
    }
    if (force || document.activeElement !== view.pan) view.pan.value = String(channel.pan);
    view.mute.classList.toggle("on", channel.mute);
    view.solo.classList.toggle("on", channel.solo);
  });
  if (force || document.activeElement !== master) {
    master.value = String(Math.round(state.master / 10));
    masterRead.textContent = master.value;
  }
  link.textContent = state.pico ? "Pico connected" : (state.demo ? "Demo tones" : "No Pico");
  link.classList.toggle("ok", Boolean(state.pico));
}

master.addEventListener("input", () => {
  masterRead.textContent = master.value;
  clearTimeout(timers.get("master"));
  timers.set("master", setTimeout(() => {
    post("/api/master", { master: Number(master.value) * 10 });
  }, 40));
});

document.querySelector("#save").addEventListener("click", () => {
  post("/api/preset/save", { slot: document.querySelector("#slot").value });
});
document.querySelector("#load").addEventListener("click", () => {
  post("/api/preset/load", { slot: document.querySelector("#slot").value }).then(() => load(true));
});

async function load(force) {
  const response = await fetch("/api/state");
  apply(await response.json(), force);
}

async function meters() {
  const response = await fetch("/api/meters");
  const data = await response.json();
  data.in.forEach((level, index) => {
    views[index].bar.style.height = level + "%";
  });
  document.querySelector("#out-l").style.height = data.out[0] + "%";
  document.querySelector("#out-r").style.height = data.out[1] + "%";
}

load(true);
setInterval(() => { load(false); meters(); }, 200);
