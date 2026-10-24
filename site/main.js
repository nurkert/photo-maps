"use strict";

// German texts of the landing page; the English ones are in the HTML.
const GERMAN = {
  wiki: "Wiki",
  heroTitle: "Deine Fotos auf Minecraft-Karten",
  heroText: "Crafte einen Foto-Code, scanne ihn mit dem Handy, häng dein Foto an die Wand. Für Paper und Spigot, Java und Bedrock.",
  download: "Herunterladen",
  start: "Loslegen",
  howTitle: "So funktioniert's",
  step1Title: "Foto-Code craften",
  step1Text: "Vier Tintenbeutel um eine leere Karte.",
  step2Title: "Scannen",
  step2Text: "Rechtsklick, und er wird zu einer Karte mit QR-Code. Scanne ihn mit der Handykamera.",
  step3Title: "Bild wählen",
  step3Text: "Foto, Selfie oder aus der Galerie. Größe wählen, verschieben, zoomen und Felder weglassen.",
  step4Title: "Aufhängen",
  step4Text: "Die Karten landen im Inventar. Steck eine in einen Rahmen, die anderen hängen sich daneben.",
  featuresTitle: "Funktionen",
  f1Title: "Fühlt sich an wie Minecraft",
  f1Text: "Die Seite auf dem Handy sieht aus wie ein Minecraft-Menü: Schieberegler, Buttons, Tooltips und Toasts.",
  f2Title: "Java und Bedrock",
  f2Text: "Der Foto-Code hat auf beiden Editionen eine eigene Textur. Resource Pack und Geyser-Mapping installieren sich selbst.",
  f3Title: "Verschlüsselte Uploads",
  f3Text: "HTTPS mit TLS 1.2/1.3, Let's-Encrypt-Zertifikate oder ein selbst signiertes, ablaufende Codes und eine strenge Sicherheitsrichtlinie.",
  f4Title: "Echte Kartenfarben",
  f4Text: "Bilder werden in die Kartenpalette gedithert, und das Handy zeigt vorher genau, wie sie aussehen werden.",
  f5Title: "Hängt sich selbst auf",
  f5Text: "Ein Klick auf einen Rahmen und jeder Teil des Bildes findet seinen Platz, mit Rücksicht auf WorldGuard und andere Schutzplugins.",
  f6Title: "Survival-freundlich",
  f6Text: "Jede Karte eines Bildes kostet eine leere Karte. Bilder überstehen Neustarts und lassen sich kopieren wie jede Karte.",
  ctaTitle: "Lust, es auszuprobieren?",
  ctaText: "Jar in den plugins-Ordner, Server starten. Keine Datenbank, kein extra Webserver, für LAN-Server keine Einrichtung.",
  readWiki: "Zum Wiki",
  footer: "PhotoMaps ist Open Source unter der GNU GPL v3.0. Kein offizielles Minecraft-Produkt, nicht verbunden mit Mojang oder Microsoft.",
};

const english = {};
for (const element of document.querySelectorAll("[data-i18n]")) {
  english[element.dataset.i18n] = element.textContent;
}

function apply(language) {
  const texts = language === "de" ? GERMAN : english;
  for (const element of document.querySelectorAll("[data-i18n]")) {
    element.textContent = texts[element.dataset.i18n] ?? english[element.dataset.i18n];
  }
  document.documentElement.lang = language;
  document.getElementById("language").textContent = language === "de" ? "EN" : "DE";
  try {
    localStorage.setItem("language", language);
  } catch {
    // private mode: the choice is just not remembered
  }
}

let language = "en";
try {
  language = localStorage.getItem("language") || (navigator.language.startsWith("de") ? "de" : "en");
} catch {
  language = navigator.language.startsWith("de") ? "de" : "en";
}
apply(language);

document.getElementById("language").addEventListener("click", () => {
  language = language === "de" ? "en" : "de";
  apply(language);
});
