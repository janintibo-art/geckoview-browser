"use strict";
const p = new URLSearchParams(location.search);
  const host = p.get("host") || "";
  const via = p.get("via") || "";
  const to = p.get("to") || "";

  document.getElementById("host").textContent = host;
  document.getElementById("via").textContent =
    via && via !== host ? "correspond a la regle : " + via : "";

  document.getElementById("back").onclick = () => {
    if (history.length > 1) history.back();
    else location.href = browser.runtime.getURL("search.html");
  };

  document.getElementById("go").onclick = async () => {
    try { await browser.runtime.sendMessage({ type: "bypass", host: host }); } catch (e) {}
    location.href = to || ("https://" + host);
  };
