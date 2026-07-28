"use strict";

const DEFAULT_SOURCES = [
  {
    id: "israel-national-news",
    name: "Israel National News",
    url: "https://www.israelnn.com/data/news.xml",
    fallback: "https://news.google.com/rss/search?q=site%3Aisraelnationalnews.com&hl=en-US&gl=US&ceid=US%3Aen",
    enabled: true,
    lang: "en"
  },
  {
    id: "jerusalem-post",
    name: "The Jerusalem Post",
    url: "https://www.jpost.com/rss/rssfeedsfrontpage.aspx",
    fallback: "https://news.google.com/rss/search?q=site%3Ajpost.com&hl=en-US&gl=US&ceid=US%3Aen",
    enabled: true,
    lang: "en"
  },
  {
    id: "fox-world",
    name: "Fox News World",
    url: "https://moxie.foxnews.com/google-publisher/world.xml",
    fallback: "https://news.google.com/rss/search?q=site%3Afoxnews.com%2Fworld&hl=en-US&gl=US&ceid=US%3Aen",
    enabled: true,
    lang: "en"
  },
  {
    id: "bbc-mundo",
    name: "BBC News Mundo",
    url: "https://feeds.bbci.co.uk/mundo/rss.xml",
    fallback: "https://news.google.com/rss/search?q=site%3Abbc.com%2Fmundo&hl=es-419&gl=US&ceid=US%3Aes-419",
    enabled: true,
    lang: "es"
  },
  {
    id: "bbc-world",
    name: "BBC News Internacional",
    url: "https://feeds.bbci.co.uk/news/world/rss.xml",
    fallback: "https://news.google.com/rss/search?q=site%3Abbc.com%2Fnews%2Fworld&hl=en-US&gl=US&ceid=US%3Aen",
    enabled: true,
    lang: "en"
  },
  {
    id: "montevideo-portal",
    name: "Montevideo Portal",
    url: "https://www.montevideo.com.uy/anxml.aspx?59",
    fallback: "https://news.google.com/rss/search?q=site%3Amontevideo.com.uy&hl=es-419&gl=UY&ceid=UY%3Aes-419",
    enabled: true,
    lang: "es"
  }
];

const STORAGE = {
  sources: "monitor.sources.v1",
  keywords: "monitor.keywords.v1",
  hours: "monitor.hours.v1",
  keywordFilter: "monitor.keywordFilter.v1",
  translations: "monitor.translations.v1"
};

const $ = selector => document.querySelector(selector);
const newsList = $("#newsList");
const timeWindow = $("#timeWindow");
const keywordFilter = $("#keywordFilter");
let sources = loadJson(STORAGE.sources, DEFAULT_SOURCES);
let keywords = loadJson(STORAGE.keywords, []);
let translations = loadJson(STORAGE.translations, {});
let allItems = [];
let isRefreshing = false;
let lastRefreshAt = 0;

function loadJson(key, fallback) {
  try {
    const stored = localStorage.getItem(key);
    return stored ? JSON.parse(stored) : JSON.parse(JSON.stringify(fallback));
  } catch (_) {
    return JSON.parse(JSON.stringify(fallback));
  }
}

function saveJson(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)); } catch (_) {}
}

function uid(prefix = "source") {
  return prefix + "-" + Date.now().toString(36) + "-" + Math.random().toString(36).slice(2, 7);
}

function setStatus(kind, title, detail) {
  const pulse = $("#statusPulse");
  pulse.className = "pulse" + (kind ? " " + kind : "");
  $("#statusTitle").textContent = title;
  $("#statusDetail").textContent = detail;
}

function setBusy(busy) {
  isRefreshing = busy;
  $("#refreshButton").disabled = busy;
  $("#floatingRefresh").disabled = busy;
  $("#refreshIcon").classList.toggle("rotating", busy);
  $("#floatingRefresh").classList.toggle("rotating", busy);
}

function decodeHtml(text) {
  const area = document.createElement("textarea");
  area.innerHTML = text || "";
  return area.value;
}

function stripHtml(text) {
  const div = document.createElement("div");
  div.innerHTML = text || "";
  return (div.textContent || "").replace(/\s+/g, " ").trim();
}

function nodeText(node, names) {
  for (const name of names) {
    const found = node.getElementsByTagName(name)[0];
    if (found && found.textContent) return found.textContent.trim();
  }
  return "";
}

function nodeLink(node) {
  const linkNodes = node.getElementsByTagName("link");
  for (const link of linkNodes) {
    const href = link.getAttribute && link.getAttribute("href");
    const rel = link.getAttribute && link.getAttribute("rel");
    if (href && (!rel || rel === "alternate")) return href.trim();
    if (link.textContent && /^https?:/i.test(link.textContent.trim())) return link.textContent.trim();
  }
  return nodeText(node, ["guid", "id"]);
}

function parseDate(value) {
  if (!value) return Date.now();
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : Date.now();
}

function parseFeed(xml, source) {
  const doc = new DOMParser().parseFromString(xml, "text/xml");
  if (doc.querySelector("parsererror")) throw new Error("La respuesta no es un RSS válido");
  const rssItems = Array.from(doc.getElementsByTagName("item"));
  const atomItems = Array.from(doc.getElementsByTagName("entry"));
  const nodes = rssItems.length ? rssItems : atomItems;
  if (!nodes.length) throw new Error("La fuente no contiene titulares");

  return nodes.map(node => {
    const rawTitle = nodeText(node, ["title"]);
    const title = decodeHtml(stripHtml(rawTitle));
    const link = nodeLink(node);
    const description = stripHtml(nodeText(node, ["description", "summary", "content"]));
    const dateValue = nodeText(node, ["pubDate", "published", "updated", "dc:date"]);
    return {
      id: source.id + "-" + simpleHash(title + link),
      sourceId: source.id,
      sourceName: source.name,
      sourceLang: source.lang || "",
      title,
      link,
      summary: decodeHtml(description),
      timestamp: parseDate(dateValue)
    };
  }).filter(item => item.title && /^https?:/i.test(item.link));
}

function simpleHash(value) {
  let hash = 0;
  for (let i = 0; i < value.length; i++) hash = ((hash << 5) - hash + value.charCodeAt(i)) | 0;
  return Math.abs(hash).toString(36);
}

async function fetchText(url, timeoutMs = 12000) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const response = await fetch(url, { cache: "no-store", signal: controller.signal });
    if (!response.ok) throw new Error("HTTP " + response.status);
    return await response.text();
  } finally {
    clearTimeout(timer);
  }
}

async function tryFeed(url, source) {
  const attempts = [
    url,
    "https://api.allorigins.win/raw?url=" + encodeURIComponent(url)
  ];
  let lastError;
  for (const attempt of attempts) {
    try {
      const text = await fetchText(attempt);
      return parseFeed(text, source);
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError || new Error("No fue posible leer la fuente");
}

async function loadSource(source) {
  try {
    return await tryFeed(source.url, source);
  } catch (firstError) {
    if (source.fallback) {
      try { return await tryFeed(source.fallback, source); } catch (_) {}
    }
    throw firstError;
  }
}

function deduplicate(items) {
  const seen = new Set();
  return items.filter(item => {
    const key = item.title.toLocaleLowerCase("es").replace(/[^\p{L}\p{N}]+/gu, " ").trim();
    if (!key || seen.has(key)) return false;
    seen.add(key);
    return true;
  });
}

function currentFilteredItems() {
  const hours = Number(timeWindow.value || 1);
  const cutoff = Date.now() - hours * 60 * 60 * 1000;
  const activeKeywords = keywords.map(k => k.toLocaleLowerCase("es").trim()).filter(Boolean);
  const useKeywords = keywordFilter.checked && activeKeywords.length > 0;
  return allItems.filter(item => {
    if (item.timestamp < cutoff) return false;
    if (!useKeywords) return true;
    const haystack = (item.title + " " + item.summary + " " + item.sourceName).toLocaleLowerCase("es");
    return activeKeywords.some(keyword => haystack.includes(keyword));
  });
}

function formatRelative(timestamp) {
  const minutes = Math.max(0, Math.round((Date.now() - timestamp) / 60000));
  if (minutes < 1) return "ahora";
  if (minutes < 60) return "hace " + minutes + " min";
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return "hace " + hours + (hours === 1 ? " hora" : " horas");
  const days = Math.floor(hours / 24);
  return "hace " + days + (days === 1 ? " día" : " días");
}

function isProbablySpanish(text) {
  const sample = " " + text.toLocaleLowerCase("es") + " ";
  const clues = [" el ", " la ", " los ", " las ", " de ", " del ", " que ", " para ", " por ", " una ", " en ", " y ", " tras ", " según "];
  return clues.filter(clue => sample.includes(clue)).length >= 3 || /[¿¡ñáéíóúü]/i.test(text);
}

function detectLanguage(item) {
  if (/[\u0590-\u05ff]/.test(item.title)) return "he";
  if (/[\u0600-\u06ff]/.test(item.title)) return "ar";
  if (item.sourceLang) return item.sourceLang;
  return isProbablySpanish(item.title) ? "es" : "en";
}

function translationKey(item) {
  return detectLanguage(item) + ":" + item.title;
}

async function translateTitle(item) {
  const lang = detectLanguage(item);
  if (lang === "es" || isProbablySpanish(item.title)) return item.title;
  const key = translationKey(item);
  if (translations[key]) return translations[key];
  const endpoint = "https://api.mymemory.translated.net/get?q=" + encodeURIComponent(item.title.slice(0, 450)) + "&langpair=" + encodeURIComponent(lang + "|es");
  try {
    const response = await fetch(endpoint, { cache: "no-store" });
    if (!response.ok) throw new Error("HTTP " + response.status);
    const data = await response.json();
    const translated = decodeHtml(data && data.responseData && data.responseData.translatedText || "").trim();
    if (!translated || translated.toLocaleLowerCase("es") === item.title.toLocaleLowerCase("es")) return item.title;
    translations[key] = translated;
    const entries = Object.entries(translations);
    if (entries.length > 220) translations = Object.fromEntries(entries.slice(-180));
    saveJson(STORAGE.translations, translations);
    return translated;
  } catch (_) {
    return item.title;
  }
}

function openUrl(url) {
  if (window.Android && typeof window.Android.openUrl === "function") window.Android.openUrl(url);
  else window.open(url, "_blank", "noopener");
}

function translatedArticleUrl(url) {
  return "https://translate.google.com/translate?sl=auto&tl=es&u=" + encodeURIComponent(url);
}

function shareItem(item) {
  if (window.Android && typeof window.Android.share === "function") window.Android.share(item.title, item.link);
  else if (navigator.share) navigator.share({ title: item.title, url: item.link }).catch(() => {});
}

function emptyMessage(title, message) {
  newsList.innerHTML = "<div class=\"empty-state\"><div class=\"empty-icon\">⌁</div><h2>" + title + "</h2><p>" + message + "</p></div>";
}

function renderNews(errors = []) {
  const filtered = currentFilteredItems();
  $("#visibleCount").textContent = String(filtered.length);
  $("#foundCount").textContent = String(allItems.length);
  $("#sourceCount").textContent = String(sources.filter(s => s.enabled).length);
  renderKeywordStrip();

  newsList.innerHTML = "";
  if (errors.length) {
    const warning = document.createElement("div");
    warning.className = "load-errors";
    warning.textContent = "No respondieron: " + errors.join(", ") + ". Los demás medios se muestran normalmente.";
    newsList.appendChild(warning);
  }

  if (!filtered.length) {
    const hours = Number(timeWindow.value || 1);
    const hasKeywordFilter = keywordFilter.checked && keywords.length > 0;
    const holder = document.createElement("div");
    holder.className = "empty-state";
    holder.innerHTML = "<div class=\"empty-icon\">⌁</div><h2>No hay titulares para mostrar</h2><p>" +
      (hasKeywordFilter
        ? "No hubo coincidencias con tus palabras clave durante el período elegido. Prueba desactivar el filtro o ampliar el tiempo."
        : "No se encontraron publicaciones durante las últimas " + hours + (hours === 1 ? " hora." : " horas.")) +
      "</p>";
    newsList.appendChild(holder);
    return;
  }

  const translationJobs = [];
  const fragment = document.createDocumentFragment();
  for (const item of filtered) {
    const card = $("#newsTemplate").content.firstElementChild.cloneNode(true);
    card.querySelector(".source-pill").textContent = item.sourceName;
    const time = card.querySelector("time");
    time.textContent = formatRelative(item.timestamp);
    time.dateTime = new Date(item.timestamp).toISOString();

    const translatedTitle = card.querySelector(".translated-title");
    const originalTitle = card.querySelector(".original-title");
    const language = detectLanguage(item);
    translatedTitle.textContent = item.title;
    originalTitle.textContent = "";
    originalTitle.hidden = true;
    if (language !== "es" && !isProbablySpanish(item.title)) {
      const cached = translations[translationKey(item)];
      if (cached) {
        translatedTitle.textContent = cached;
        originalTitle.textContent = "Original: " + item.title;
        originalTitle.hidden = false;
      } else {
        translatedTitle.classList.add("loading-translation");
        translationJobs.push({ item, translatedTitle, originalTitle });
      }
    }

    const summary = card.querySelector(".news-summary");
    if (item.summary && item.summary.toLocaleLowerCase("es") !== item.title.toLocaleLowerCase("es")) summary.textContent = item.summary;
    else summary.hidden = true;

    card.querySelector(".open-button").addEventListener("click", () => openUrl(item.link));
    card.querySelector(".translate-button").addEventListener("click", () => openUrl(translatedArticleUrl(item.link)));
    card.querySelector(".share-button").addEventListener("click", () => shareItem(item));
    translatedTitle.addEventListener("click", () => openUrl(item.link));
    fragment.appendChild(card);
  }
  newsList.appendChild(fragment);
  runTranslations(translationJobs);
}

async function runTranslations(jobs) {
  const limited = jobs.slice(0, 24);
  for (let i = 0; i < limited.length; i += 2) {
    const batch = limited.slice(i, i + 2);
    await Promise.all(batch.map(async job => {
      const translated = await translateTitle(job.item);
      if (!document.body.contains(job.translatedTitle)) return;
      job.translatedTitle.classList.remove("loading-translation");
      job.translatedTitle.textContent = translated;
      if (translated !== job.item.title) {
        job.originalTitle.textContent = "Original: " + job.item.title;
        job.originalTitle.hidden = false;
      }
    }));
  }
}

async function refreshNews(userInitiated = true) {
  if (isRefreshing) return;
  const active = sources.filter(source => source.enabled);
  if (!active.length) {
    allItems = [];
    renderNews();
    setStatus("error", "No hay fuentes activas", "Abre Configuración y activa o agrega al menos un medio.");
    return;
  }

  setBusy(true);
  setStatus("loading", "Revisando titulares", "Consultando " + active.length + (active.length === 1 ? " fuente…" : " fuentes…"));
  const collected = [];
  const errors = [];
  let completed = 0;

  await Promise.all(active.map(async source => {
    try {
      const items = await loadSource(source);
      collected.push(...items);
    } catch (_) {
      errors.push(source.name);
    } finally {
      completed++;
      setStatus("loading", "Revisando titulares", completed + " de " + active.length + " fuentes procesadas");
    }
  }));

  allItems = deduplicate(collected).sort((a, b) => b.timestamp - a.timestamp);
  renderNews(errors);
  lastRefreshAt = Date.now();
  const visible = currentFilteredItems().length;
  const time = new Intl.DateTimeFormat("es-UY", { hour: "2-digit", minute: "2-digit" }).format(new Date());
  $("#lastUpdate").textContent = "Actualizado a las " + time;

  if (!allItems.length && errors.length === active.length) {
    setStatus("error", "No se pudieron leer las fuentes", "Comprueba la conexión a internet y vuelve a intentarlo.");
  } else if (errors.length) {
    setStatus("error", visible + " titulares visibles", errors.length + (errors.length === 1 ? " fuente no respondió." : " fuentes no respondieron."));
  } else {
    setStatus("", visible + (visible === 1 ? " titular visible" : " titulares visibles"), allItems.length + " publicaciones revisadas. Próxima actualización automática en cinco minutos.");
  }
  setBusy(false);

  if (userInitiated && window.Android && typeof window.Android.toast === "function") {
    window.Android.toast(visible ? visible + " titulares para revisar" : "No hay nuevos titulares con estos filtros");
  }
}

function renderSources() {
  const list = $("#sourcesList");
  list.innerHTML = "";
  for (const source of sources) {
    const row = document.createElement("div");
    row.className = "source-row";
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.checked = !!source.enabled;
    checkbox.setAttribute("aria-label", "Activar " + source.name);
    checkbox.addEventListener("change", () => {
      source.enabled = checkbox.checked;
      saveJson(STORAGE.sources, sources);
      $("#sourceCount").textContent = String(sources.filter(s => s.enabled).length);
    });
    const copy = document.createElement("div");
    copy.className = "source-copy";
    const name = document.createElement("strong");
    name.textContent = source.name;
    const url = document.createElement("span");
    url.textContent = source.url;
    copy.append(name, url);
    const remove = document.createElement("button");
    remove.className = "delete-button";
    remove.type = "button";
    remove.textContent = "×";
    remove.setAttribute("aria-label", "Eliminar " + source.name);
    remove.addEventListener("click", () => {
      sources = sources.filter(item => item.id !== source.id);
      saveJson(STORAGE.sources, sources);
      renderSources();
    });
    row.append(checkbox, copy, remove);
    list.appendChild(row);
  }
}

function renderKeywords() {
  const list = $("#keywordsList");
  list.innerHTML = "";
  for (const keyword of keywords) {
    const chip = document.createElement("div");
    chip.className = "editor-chip";
    const text = document.createElement("span");
    text.textContent = keyword;
    const remove = document.createElement("button");
    remove.type = "button";
    remove.textContent = "×";
    remove.addEventListener("click", () => {
      keywords = keywords.filter(item => item !== keyword);
      saveJson(STORAGE.keywords, keywords);
      renderKeywords();
      updateKeywordUi();
      renderNews();
    });
    chip.append(text, remove);
    list.appendChild(chip);
  }
  if (!keywords.length) {
    const helper = document.createElement("p");
    helper.className = "helper";
    helper.textContent = "Todavía no agregaste palabras clave.";
    list.appendChild(helper);
  }
}

function renderKeywordStrip() {
  const strip = $("#keywordStrip");
  strip.innerHTML = "";
  const active = keywordFilter.checked && keywords.length > 0;
  strip.classList.toggle("hidden", !active);
  if (!active) return;
  for (const keyword of keywords) {
    const chip = document.createElement("span");
    chip.className = "keyword-chip";
    chip.textContent = keyword;
    strip.appendChild(chip);
  }
}

function updateKeywordUi() {
  $("#keywordCount").textContent = String(keywords.length);
  keywordFilter.disabled = keywords.length === 0;
  if (!keywords.length) keywordFilter.checked = false;
  $("#keywordFilter").closest(".switch-row").style.opacity = keywords.length ? "1" : ".55";
  renderKeywordStrip();
}

function openModal(id) {
  const modal = document.getElementById(id);
  modal.classList.remove("hidden");
  document.body.style.overflow = "hidden";
}

function closeModal(id) {
  const modal = document.getElementById(id);
  modal.classList.add("hidden");
  if (!document.querySelector(".modal:not(.hidden)")) document.body.style.overflow = "";
  if (id === "settingsModal" || id === "keywordsModal") refreshNews(false);
}

function bindEvents() {
  $("#refreshButton").addEventListener("click", () => refreshNews(true));
  $("#floatingRefresh").addEventListener("click", () => refreshNews(true));
  $("#settingsButton").addEventListener("click", () => { renderSources(); openModal("settingsModal"); });
  $("#keywordsButton").addEventListener("click", () => { renderKeywords(); openModal("keywordsModal"); setTimeout(() => $("#keywordInput").focus(), 150); });
  $("#addSourceButton").addEventListener("click", () => openModal("sourceModal"));

  document.querySelectorAll("[data-close]").forEach(button => {
    button.addEventListener("click", () => closeModal(button.dataset.close));
  });
  document.querySelectorAll(".modal").forEach(modal => {
    modal.addEventListener("click", event => { if (event.target === modal) closeModal(modal.id); });
  });

  timeWindow.addEventListener("change", () => {
    localStorage.setItem(STORAGE.hours, timeWindow.value);
    renderNews();
    const visible = currentFilteredItems().length;
    setStatus("", visible + (visible === 1 ? " titular visible" : " titulares visibles"), "Filtro temporal actualizado.");
  });

  keywordFilter.addEventListener("change", () => {
    localStorage.setItem(STORAGE.keywordFilter, keywordFilter.checked ? "1" : "0");
    renderNews();
  });

  $("#keywordForm").addEventListener("submit", event => {
    event.preventDefault();
    const input = $("#keywordInput");
    const value = input.value.trim();
    if (!value) return;
    if (!keywords.some(item => item.toLocaleLowerCase("es") === value.toLocaleLowerCase("es"))) keywords.push(value);
    input.value = "";
    keywordFilter.checked = true;
    saveJson(STORAGE.keywords, keywords);
    localStorage.setItem(STORAGE.keywordFilter, "1");
    renderKeywords();
    updateKeywordUi();
    renderNews();
  });

  $("#sourceForm").addEventListener("submit", event => {
    event.preventDefault();
    const name = $("#sourceName").value.trim();
    const url = $("#sourceUrl").value.trim();
    if (!name || !/^https?:\/\//i.test(url)) return;
    sources.push({ id: uid(), name, url, enabled: true, lang: "" });
    saveJson(STORAGE.sources, sources);
    event.target.reset();
    closeModal("sourceModal");
    renderSources();
    if (window.Android && typeof window.Android.toast === "function") window.Android.toast("Fuente agregada");
  });

  $("#restoreSourcesButton").addEventListener("click", () => {
    sources = JSON.parse(JSON.stringify(DEFAULT_SOURCES));
    saveJson(STORAGE.sources, sources);
    renderSources();
  });

  document.addEventListener("visibilitychange", () => {
    if (!document.hidden && Date.now() - lastRefreshAt > 2 * 60 * 1000) refreshNews(false);
  });
}

function initialize() {
  const savedHours = localStorage.getItem(STORAGE.hours);
  if (savedHours && timeWindow.querySelector("option[value='" + savedHours + "']")) timeWindow.value = savedHours;
  const savedFilter = localStorage.getItem(STORAGE.keywordFilter);
  keywordFilter.checked = savedFilter === null ? keywords.length > 0 : savedFilter === "1";
  updateKeywordUi();
  renderSources();
  renderKeywords();
  bindEvents();
  refreshNews(false);
  setInterval(() => refreshNews(false), 5 * 60 * 1000);
}

window.refreshFromAndroid = function () {
  if (Date.now() - lastRefreshAt > 2 * 60 * 1000) refreshNews(false);
};

document.addEventListener("DOMContentLoaded", initialize);
