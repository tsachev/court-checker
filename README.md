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
service add `Environment=COURT_CHECKER_DIR=...` to `systemd/court-checker.service.in` and re-run the installer).

## Requirements

- Clojure CLI (`/usr/bin/clojure`) and a JDK
- `notify-send` (libnotify)
- systemd user session (for scheduling)

## Usage

```
clojure -M:run 62325/2023
```

Without a valid argument it prints a usage message and exits with code 2.

### REPL

Start a REPL with `clojure` (or `clj`), then:

```clojure
(require 'checker)
(def c (checker/parse-case-id "62325/2023"))   ;=> {:id "62325/2023", :number "62325", :year "2023"}
(checker/case-dir c)                            ;; folder with saved acts and downloads
(checker/run-check c)
```

All public functions work with the parsed id returned by `parse-case-id`.
Loading the namespace has no side effects, and `parse-case-id` / `run-check` throw an
`ex-info` on fatal errors (it never calls `System/exit`). Only `checker/-main`
(used by `clojure -M:run`) turns errors into exit codes (1 = error, 2 = bad
argument) and shuts down the agent thread pool.

## Install as a systemd user timer

Clone the repository anywhere (for example `~/src/court-checker`) and run the
installer from there, passing the case id:

```
systemd/install.sh 62325/2023
```

It renders the `systemd/*.in` templates using the folder the repository lives
in (written as `%h/...` when it is under your home folder), the `clojure` found on `PATH` and the case id, writes
`court-checker-<number>-<year>.service` and `.timer` to
`~/.config/systemd/user/` (or `$XDG_CONFIG_HOME/systemd/user/`), then reloads
systemd and enables the timer. Run it once per case to watch several cases.

Use `systemd/install.sh --dry-run 62325/2023` to preview the generated units and
`systemd/install.sh --uninstall 62325/2023` to remove them. If you move the
repository, run the installer again.

The timer runs 2 minutes after login and then every hour
(`OnUnitActiveSec` in `systemd/court-checker.timer.in`).

Useful commands (unit name is `court-checker-<number>-<year>`):

```
systemctl --user list-timers 'court-checker-*'
systemctl --user start court-checker-62325-2023.service   # run once now
journalctl --user -u court-checker-62325-2023              # view output
```

## License

[MIT](LICENSE)
