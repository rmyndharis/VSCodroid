// Puts the latest release and the star count on the page.
//
// assets/live.json is written when the site is deployed, by
// scripts/fetch-site-live.py, so this asks the site itself and nothing else.
// Without the file, or without this script, every element keeps the text it
// was written with.
fetch("assets/live.json")
  .then((response) => (response.ok ? response.json() : {}))
  .then((live) => {
    for (const element of document.querySelectorAll("[data-live]")) {
      const value = live[element.dataset.live];
      if (typeof value === "string") {
        element.textContent = value;
        // The star count sits in a box that stays hidden until it has one.
        const box = element.closest("[hidden]");
        if (box) box.hidden = false;
      }
    }
  })
  .catch(() => {});
