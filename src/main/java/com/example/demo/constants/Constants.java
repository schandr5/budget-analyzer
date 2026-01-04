package com.example.demo.constants;

import java.util.Set;
import java.util.Map;
import java.util.regex.Pattern;

public final class Constants {

    private Constants() {
        // Prevent instantiation
    }

    public static final double UPPER_BOUND = 0.3;
    public static final double LOWER_BOUND = 0.1;

    // --- AI / Text-to-SQL constants ---
    public static final String OLLAMA_GENERATE_URL = "http://localhost:11434/api/generate";
    public static final int OLLAMA_MAX_RETRIES = 3;
    public static final Pattern SQL_SELECT_PATTERN = Pattern.compile("(?is)\\bselect\\b[\\s\\S]*");
    public static final Pattern SQL_BUDGET_ID_EQUALS_PATTERN = Pattern.compile("(?is)\\bbudget_id\\b\\s*=\\s*(\\d+)");
    public static final Pattern PROMPT_REQUIRED_BUDGET_ID_PATTERN = Pattern.compile("(?m)^- budget_id must be (\\d+)\\s*$");
    public static final Pattern PROMPT_BUDGET_ID_FALLBACK_PATTERN = Pattern.compile("(?m)\\bbudget_id\\s*=\\s*(\\d+)");
    public static final Pattern PROMPT_REQUIRED_CATEGORY_PATTERN = Pattern.compile("(?m)^- category must be '(.+)'\\s*$");

    // Tokens that indicate PostgreSQL-only / non-MySQL syntax that we want to reject.
    public static final Set<String> SQL_BANNED_TOKENS = Set.of(
            " ilike ",
            "interval '",
            "::",
            " extract(",
            "date_trunc",
            " date_part",
            " to_char",
            " to_date",
            " to_timestamp",
            " at time zone",
            "generate_series",
            "distinct on",
            " filter (where ",
            " returning ",
            " similar to ",
            " nulls last",
            " nulls first",
            " on conflict",
            " intersect ",
            " except ",
            " serial ",
            " bigserial ",
            " generated always as identity",
            " timestamptz",
            " time with time zone",
            " without time zone",
            " jsonb",
            " hstore",
            " uuid",
            " array[",
            " unnest(",
            " || ",
            " do $$",
            " language plpgsql",
            " create extension",
            " vacuum ",
            " analyze ",
            " regexp_replace(",
            " cascade ",
            " from transactions ",
            " join transactions ",
            " transactions.",
            " from budgets ",
            " join budgets ",
            " fetch first ",
            " offset "
    );

    /**
     * Month name -> month number (1-12). Used to make date prompts deterministic.
     */
    public static final Map<String, Integer> MONTH_NAME_TO_NUMBER = Map.ofEntries(
            Map.entry("january", 1),
            Map.entry("february", 2),
            Map.entry("march", 3),
            Map.entry("april", 4),
            Map.entry("may", 5),
            Map.entry("june", 6),
            Map.entry("july", 7),
            Map.entry("august", 8),
            Map.entry("september", 9),
            Map.entry("october", 10),
            Map.entry("november", 11),
            Map.entry("december", 12)
    );
}