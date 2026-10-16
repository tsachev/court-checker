# court-checker

A Clojure script that watches a case on the Sofia District Court website
(`srs.justice.bg`, "Справки по дела") and sends a desktop notification when new
documents appear. The court's case-search page is hard-coded in `build-url`, so
it only works for cases of that court.

## How it works

1. Builds the case URL from a `<number>/<year>` argument.
2. Fetches the page and parses every act row in `table.results-table`
   (index, type, date, judge, file link, motives link).
3. Compares them with the previously saved state in the case folder.
4. Downloads act files (and motives) that are available but not yet stored.
5. Notifies via `notify-send` if something changed (new act, a file or motives
   that became available, or an edited row):
   - **critical** if a changed act is a `Решение` (judgment);
   - **normal** otherwise.

The first run for a case only stores a baseline and does not notify.
If a download fails it is retried on the next run.

## Data location

Data is stored per case in `<base>/<number>-<year>/` (the `/` in the case id
is replaced by `-`):

```
~/.local/share/court-checker/62325-2023/
├── acts.edn                          # saved state (all acts + local file names)
├── 001_2023-11-24_Разпореждане.pdf   # downloaded acts: <index>_<date>_<type>.<ext>
└── ...                               # motives get a `_motives` suffix
```

The base directory defaults to `~/.local/share/court-checker` and can be changed
with the `COURT_CHECKER_DIR` environment variable (e.g. `/var/lib/court-checker`,
which must be writable by the user running the script; for the systemd user
service add `Environment=COURT_CHECKER_DIR=...` to `court-checker.service`).

## Requirements

- Clojure CLI (`/usr/bin/clojure`) and a JDK
- `notify-send` (libnotify)
- systemd user session (for scheduling)

## Usage

```
clojure -M:run <number>/<year>
clojure -M:run 62325/2023
```

Without a valid argument it prints a usage message and exits with code 2.

## Install as a systemd user timer

Edit the case number in `court-checker.service` (`ExecStart` line), then:

```
mkdir -p ~/.config/systemd/user
cp court-checker.service court-checker.timer ~/.config/systemd/user/
systemctl --user daemon-reload
systemctl --user enable --now court-checker.timer
```

The timer runs 2 minutes after login and then every hour
(`OnUnitActiveSec` in `court-checker.timer`).

The service uses `%h/.local/bin/court-checker` as its working directory;
adjust it if the project lives elsewhere.

Useful commands:

```
systemctl --user list-timers court-checker.timer
systemctl --user start court-checker.service     # run once now
journalctl --user -u court-checker                # view output
systemctl --user disable --now court-checker.timer
```

## License

[MIT](LICENSE)
