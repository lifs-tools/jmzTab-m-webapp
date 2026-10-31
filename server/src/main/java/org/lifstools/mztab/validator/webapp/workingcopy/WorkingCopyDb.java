/*
 * Copyright 2026 LIFS Tools Consortium.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lifstools.mztab.validator.webapp.workingcopy;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * The exact text of one working copy in a SQLite file.
 *
 * <p>
 * Schema: {@code lines(number INTEGER PRIMARY KEY, section, text, eol)} keyed
 * by the line number itself, plus {@code meta(k, v)}. Because the number is the
 * rowid, {@code lines(from, count)} is a range scan that costs the page size
 * and not the file size, also for ~1M lines. The price is paid by insert and
 * delete: they shift the following numbers in one statement each (through
 * negative temporary numbers, as SQLite checks the primary key row by row), so
 * they cost the length of the tail, inside the engine and a single
 * transaction. Edits replace a line in place.
 *
 * <p>
 * Every statement is a {@code static final String} with named parameters and
 * runs through {@link NamedParameterJdbcTemplate}; {@code StaticSqlTest}
 * enforces this. The schema is created in {@link #open}, from the {@code CREATE_*}
 * constants, the one place to extend. Each call takes its own connection from the data source unless it
 * runs inside {@link #inTransaction}; {@link #close()} releases nothing; it
 * exists for try-with-resources. Transactions are IMMEDIATE.
 */
public final class WorkingCopyDb implements AutoCloseable {

    private static final int PAGE = 1000;

    private static final String CREATE_LINES = """
        CREATE TABLE IF NOT EXISTS lines (number INTEGER PRIMARY KEY, section TEXT NOT NULL,
        text TEXT NOT NULL, eol TEXT NOT NULL)""";
    private static final String CREATE_META = "CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)";

    /** stack is 'U' (can be undone) or 'R' (can be redone); forward and inverse are JSON step lists */
    private static final String CREATE_HISTORY = """
        CREATE TABLE IF NOT EXISTS history (seq INTEGER PRIMARY KEY AUTOINCREMENT, stack TEXT NOT NULL,
        forward TEXT NOT NULL, inverse TEXT NOT NULL)""";
    private static final String HISTORY_ADD
        = "INSERT INTO history (stack, forward, inverse) VALUES ('U', :forward, :inverse)";
    private static final String HISTORY_CLEAR_REDO = "DELETE FROM history WHERE stack = 'R'";
    private static final String HISTORY_TOP_UNDO
        = "SELECT seq, forward, inverse FROM history WHERE stack = 'U' ORDER BY seq DESC LIMIT 1";
    private static final String HISTORY_TOP_REDO
        = "SELECT seq, forward, inverse FROM history WHERE stack = 'R' ORDER BY seq ASC LIMIT 1";
    private static final String HISTORY_MOVE = "UPDATE history SET stack = :stack WHERE seq = :seq";
    private static final String HISTORY_CLEAR_ALL = "DELETE FROM history";
    private static final String HISTORY_COUNT_UNDO = "SELECT COUNT(*) FROM history WHERE stack = 'U'";
    private static final String HISTORY_CHARS
        = "SELECT COALESCE(SUM(LENGTH(forward) + LENGTH(inverse)), 0) FROM history";
    private static final String HISTORY_PRUNE_OLDEST = """
        DELETE FROM history WHERE seq IN
        (SELECT seq FROM history WHERE stack = 'U' ORDER BY seq ASC LIMIT :n)""";
    private static final String TEXT_CHARS = "SELECT COALESCE(SUM(LENGTH(text)), 0) FROM lines";
    private static final String REVISION_KEY = "revision";

    private static final String COUNT = "SELECT COUNT(*) FROM lines";
    private static final String SELECT_RANGE
        = "SELECT number, section, text, eol FROM lines WHERE number >= :from ORDER BY number LIMIT :count";
    private static final String SELECT_ONE = "SELECT number, section, text, eol FROM lines WHERE number = :number";
    private static final String SELECT_SECTIONS = """
        SELECT number, section, text, eol FROM lines WHERE section = :first OR section = :second
        ORDER BY number""";
    private static final String SELECT_FIRST_OF_SECTION
        = "SELECT number, section, text, eol FROM lines WHERE section = :section ORDER BY number LIMIT 1";
    private static final String SECTION_COUNTS = "SELECT section, COUNT(*) AS n FROM lines GROUP BY section";
    private static final String CLEAR_LINES = "DELETE FROM lines";
    private static final String INSERT_LINE
        = "INSERT INTO lines (number, section, text, eol) VALUES (:number, :section, :text, :eol)";
    private static final String REPLACE_LINE
        = "UPDATE lines SET section = :section, text = :text, eol = :eol WHERE number = :number";
    private static final String PARK_FROM = "UPDATE lines SET number = -(number + :shift) WHERE number >= :from";
    private static final String DELETE_RANGE = "DELETE FROM lines WHERE number >= :from AND number < :to";
    private static final String PARK_AFTER = "UPDATE lines SET number = -(number - :shift) WHERE number >= :from";
    private static final String UNPARK = "UPDATE lines SET number = -number WHERE number < 0";
    private static final String GET_META = "SELECT v FROM meta WHERE k = :key";
    private static final String SET_META = """
        INSERT INTO meta (k, v) VALUES (:key, :value) ON CONFLICT(k) DO UPDATE SET v = excluded.v""";
    private static final String BOM_KEY = "bom";

    private static final RowMapper<Line> LINE_MAPPER = (rs, i) -> {
        Line l = CopyText.line(rs.getLong("number"), rs.getString("text"), rs.getString("eol"));
        // the stored section equals the derived one; keep what was stored
        return new Line(l.number(), rs.getString("section"), l.cells(), l.eol());
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate tx;

    private WorkingCopyDb(SQLiteDataSource ds) {
        this.jdbc = new NamedParameterJdbcTemplate(ds);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    }

    /** Opens (creating if needed) the database in WAL mode with a 5 s busy timeout. */
    public static WorkingCopyDb open(Path file) {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(5000);
        // writers take the lock at BEGIN, so two of them wait instead of failing with SQLITE_BUSY_SNAPSHOT
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl("jdbc:sqlite:" + file.toAbsolutePath());
        WorkingCopyDb db = new WorkingCopyDb(ds);
        db.jdbc.update(CREATE_LINES, Map.of());
        db.jdbc.update(CREATE_META, Map.of());
        db.jdbc.update(CREATE_HISTORY, Map.of());
        return db;
    }

    /** Replaces all lines and the byte order mark flag. */
    public void importLines(CopyText.Parsed parsed) {
        inTransaction(() -> {
            jdbc.update(CLEAR_LINES, Map.of());
            // a fresh import starts at revision 0 with no history
            jdbc.update(HISTORY_CLEAR_ALL, Map.of());
            setRevision(0);
            List<Line> lines = parsed.lines();
            for (int i = 0; i < lines.size(); i += PAGE) {
                SqlParameterSource[] batch = new SqlParameterSource[Math.min(PAGE, lines.size() - i)];
                for (int j = 0; j < batch.length; j++) {
                    // numbered by position; the incoming Line.number() is not trusted
                    batch[j] = insertParams(i + j + 1L, lines.get(i + j));
                }
                jdbc.batchUpdate(INSERT_LINE, batch);
            }
            setMeta(BOM_KEY, Boolean.toString(parsed.bom()));
            return null;
        });
    }

    private static void check(Line l) {
        if (l.eol() == null || !(l.eol().isEmpty() || l.eol().equals("\n") || l.eol().equals("\r\n")
            || l.eol().equals("\r"))) {
            throw new IllegalArgumentException("A line ending must be LF, CRLF, CR or empty");
        }
        for (String cell : l.cells()) {
            if (cell == null || cell.indexOf('\t') >= 0 || cell.indexOf('\r') >= 0 || cell.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("A cell must not contain a tab or a line break");
            }
        }
    }

    private static SqlParameterSource insertParams(long number, Line l) {
        return new MapSqlParameterSource().addValue("number", number).addValue("section", l.section())
            .addValue("text", l.text()).addValue("eol", l.eol());
    }

    public long lineCount() {
        Long n = jdbc.queryForObject(COUNT, Map.of(), Long.class);
        return n == null ? 0 : n;
    }

    /** Up to {@code count} lines starting at line number {@code from}. */
    public List<Line> lines(long from, int count) {
        if (from < 1 || count < 0) {
            throw new IllegalArgumentException("from must be at least 1 and count not negative");
        }
        return jdbc.query(SELECT_RANGE, Map.of("from", from, "count", count), LINE_MAPPER);
    }

    public Optional<Line> line(long number) {
        return jdbc.query(SELECT_ONE, Map.of("number", number), LINE_MAPPER).stream().findFirst();
    }

    /**
     * All lines in order, fetched page by page. Not snapshot-consistent across
     * pages unless iterated inside {@link #inTransaction}; use {@link #writeTo}
     * to export a consistent copy.
     */
    public Iterator<Line> allLines() {
        return new Iterator<>() {
            private long next = 1;
            private Iterator<Line> page = List.<Line>of().iterator();
            private boolean done;

            @Override
            public boolean hasNext() {
                if (!page.hasNext() && !done) {
                    List<Line> p = lines(next, PAGE);
                    done = p.size() < PAGE;
                    next += p.size();
                    page = p.iterator();
                }
                return page.hasNext();
            }

            @Override
            public Line next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return page.next();
            }
        };
    }

    /**
     * The lines of two sections in file order, e.g. a table's header and rows,
     * read in one statement and so from one snapshot. Pass the same section
     * twice for one.
     */
    public List<Line> linesOf(String first, String second) {
        return jdbc.query(SELECT_SECTIONS, Map.of("first", first, "second", second), LINE_MAPPER);
    }

    /** The first line of a section, e.g. the first SMH header. */
    public Optional<Line> firstOf(String section) {
        return jdbc.query(SELECT_FIRST_OF_SECTION, Map.of("section", section), LINE_MAPPER).stream().findFirst();
    }

    /** The number of lines of each section that occurs. */
    public Map<String, Long> sectionCounts() {
        Map<String, Long> counts = new HashMap<>();
        jdbc.query(SECTION_COUNTS, Map.of(), (rs, i) -> counts.put(rs.getString("section"), rs.getLong("n")));
        return counts;
    }

    /** Replaces the content of line {@code number}; the number of {@code line} is ignored. */
    public void replaceLine(long number, Line line) {
        check(line);
        int changed = jdbc.update(REPLACE_LINE, new MapSqlParameterSource().addValue("number", number)
            .addValue("section", line.section()).addValue("text", line.text()).addValue("eol", line.eol()));
        if (changed != 1) {
            throw new IllegalArgumentException("There is no line " + number);
        }
    }

    /** Inserts the lines so the first becomes line {@code before}; {@code before} may be lineCount + 1. */
    public void insertLines(long before, List<Line> lines) {
        lines.forEach(WorkingCopyDb::check);
        inTransaction(() -> {
            long count = lineCount();
            if (before < 1 || before > count + 1) {
                throw new IllegalArgumentException("Cannot insert before line " + before);
            }
            if (lines.isEmpty()) {
                return null;
            }
            jdbc.update(PARK_FROM, Map.of("shift", lines.size(), "from", before));
            jdbc.update(UNPARK, Map.of());
            for (int i = 0; i < lines.size(); i++) {
                jdbc.update(INSERT_LINE, insertParams(before + i, lines.get(i)));
            }
            return null;
        });
    }

    /** Deletes {@code count} lines from {@code from} on; the later lines move up. */
    public void deleteLines(long from, int count) {
        inTransaction(() -> {
            long total = lineCount();
            if (from < 1 || count < 1 || from > total || count > total - from + 1) {
                throw new IllegalArgumentException("Cannot delete " + count + " lines from line " + from);
            }
            jdbc.update(DELETE_RANGE, Map.of("from", from, "to", from + count));
            jdbc.update(PARK_AFTER, Map.of("shift", count, "from", from + count));
            jdbc.update(UNPARK, Map.of());
            return null;
        });
    }

    /** The value stored under {@code key}, or null. */
    public String meta(String key) {
        return jdbc.query(GET_META, Map.of("key", key), (rs, i) -> rs.getString(1)).stream().findFirst()
            .orElse(null);
    }

    public void setMeta(String key, String value) {
        jdbc.update(SET_META, Map.of("key", key, "value", value));
    }

    public boolean bom() {
        return Boolean.parseBoolean(meta(BOM_KEY));
    }

    /** Writes the whole copy as UTF-8; the flag and all lines come from one snapshot. */
    public void writeTo(OutputStream out) throws IOException {
        try {
            inTransaction(() -> {
                try {
                    CopyText.write(bom(), allLines(), out);
                    return null;
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /**
     * Writes the whole copy as {@link #writeTo} does and returns the revision
     * of exactly that text: the revision, the flag and all lines come from one
     * snapshot.
     */
    public long writeRevisionTo(OutputStream out) throws IOException {
        try {
            return inTransaction(() -> {
                long revision = revision();
                try {
                    CopyText.write(bom(), allLines(), out);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return revision;
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    /** One undo-log entry: line-level steps (JSON) to redo a revision and to restore its before-image. */
    public record HistoryEntry(long seq, String forward, String inverse) {
    }

    private static final RowMapper<HistoryEntry> HISTORY_MAPPER
        = (rs, i) -> new HistoryEntry(rs.getLong("seq"), rs.getString("forward"), rs.getString("inverse"));

    /** The current revision; 0 after import. */
    public long revision() {
        String v = meta(REVISION_KEY);
        return v == null ? 0 : Long.parseLong(v);
    }

    public void setRevision(long revision) {
        setMeta(REVISION_KEY, Long.toString(revision));
    }

    /** Records a new revision as undoable and drops everything that could be redone. */
    public void addHistory(String forward, String inverse) {
        inTransaction(() -> {
            jdbc.update(HISTORY_CLEAR_REDO, Map.of());
            jdbc.update(HISTORY_ADD, Map.of("forward", forward, "inverse", inverse));
            return null;
        });
    }

    /** The newest undoable entry, if any. */
    public Optional<HistoryEntry> peekUndo() {
        return jdbc.query(HISTORY_TOP_UNDO, Map.of(), HISTORY_MAPPER).stream().findFirst();
    }

    /** The entry undone most recently, if any. */
    public Optional<HistoryEntry> peekRedo() {
        return jdbc.query(HISTORY_TOP_REDO, Map.of(), HISTORY_MAPPER).stream().findFirst();
    }

    /** Moves an entry between the undo ('U') and redo ('R') stack. */
    public void moveHistory(long seq, boolean redoable) {
        jdbc.update(HISTORY_MOVE, Map.of("stack", redoable ? "R" : "U", "seq", seq));
    }

    /** Total characters of all line texts (without endings). */
    public long textChars() {
        Long n = jdbc.queryForObject(TEXT_CHARS, Map.of(), Long.class);
        return n == null ? 0 : n;
    }

    /**
     * Drops the oldest undoable entries until at most {@code maxUndo} remain and
     * the stored history has at most {@code maxChars} characters; the newest
     * undoable entry is always kept.
     */
    public void pruneHistory(int maxUndo, long maxChars) {
        inTransaction(() -> {
            Long undo = jdbc.queryForObject(HISTORY_COUNT_UNDO, Map.of(), Long.class);
            long n = undo == null ? 0 : undo;
            if (n > maxUndo) {
                jdbc.update(HISTORY_PRUNE_OLDEST, Map.of("n", n - maxUndo));
                n = maxUndo;
            }
            while (n > 1) {
                Long chars = jdbc.queryForObject(HISTORY_CHARS, Map.of(), Long.class);
                if (chars == null || chars <= maxChars) {
                    break;
                }
                jdbc.update(HISTORY_PRUNE_OLDEST, Map.of("n", 1));
                n--;
            }
            return null;
        });
    }

    /** Runs {@code work} in one transaction; a RuntimeException rolls back and is rethrown. */
    public <T> T inTransaction(Supplier<T> work) {
        return tx.execute(status -> work.get());
    }

    @Override
    public void close() {
        // connections are taken per call, so there is nothing to release
    }
}
