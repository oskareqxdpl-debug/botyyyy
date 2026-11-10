# Bot Panel (Fabric 1.21.4)

## Jak dostać gotowy .jar
1. Konto na github.com -> nowe repozytorium -> wrzuć CAŁĄ zawartość tego folderu (z `.github`).
2. Zakładka **Actions** -> poczekaj na zielony build -> **Artifacts** -> `botmod-jar`.
3. Czerwony build? Wklej mi log błędu.

## Instalacja
Fabric Loader 1.21.4 + Fabric API 0.119.2+1.21.4 + ten .jar w folderze `mods`.

## Obsługa
- Klawisz **K** albo przycisk **Boty** w menu głównym / pauzy.
- **Ekran 1 (lista botów):** nick, IP serwera (sam adres, bez portu), **Dodaj**. Do 3 botów. Kliknij bota, żeby go obsługiwać.
  Zamknięcie okna (Esc) NIE rozłącza botów. Rozłączasz je przyciskiem **Rozłącz**.
- **Ekran 2 (bot):** zakładki **Konsola** (czat serwera + komendy), **Podgląd** (pozycja + spectate),
  **GUI** (hotbar, ekwipunek, prawy klik, okna serwera), **Ruch** (W/A/S/D, skok, obrót).
- Kompas w lobby: zakładka GUI -> kliknij slot z kompasem, potem **Prawy klik (PPM)** (albo PPM na slocie, albo klawisz R).
  Okno serwera otworzy się samo, kliknij w nim tryb.
- Zmiana serwera na proxy (`/server ...`) jest obsługiwana, bot nie ma już timeoutu.
- Przycisk `dbg` w konsoli włącza szare linie diagnostyczne z numerami pakietów.
