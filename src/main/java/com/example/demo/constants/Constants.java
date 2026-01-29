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
    public static final Pattern PROMPT_YEAR_PATTERN = Pattern.compile("\\b(20\\d{2})\\b");

    /**
     * Matches user_id = <n> (with or without a table prefix like budget.user_id / b.user_id).
     * Captures the numeric user_id as group(1).
     */
    public static final Pattern SQL_USER_ID_EQUALS_PATTERN = Pattern.compile("(?is)\\buser_id\\b\\s*=\\s*(\\d+)");

    /**
     * Detect if the SQL joins the budget table (needed for user-scoped queries).
     */
    public static final Pattern SQL_JOIN_BUDGET_PATTERN = Pattern.compile("(?is)\\bjoin\\s+`?budget`?\\b");

    /**
     * Matches budget.user_id = <n> or b.user_id = <n>. Captures the numeric user_id as group(1).
     */
    public static final Pattern SQL_BUDGET_USER_ID_EQUALS_PATTERN = Pattern.compile("(?is)\\b(?:`?budget`?|b)\\s*\\.\\s*user_id\\b\\s*=\\s*(\\d+)");

    /**
     * Detects an invalid user_id reference on the transaction table.
     */
    public static final Pattern SQL_TRANSACTION_USER_ID_PATTERN = Pattern.compile("(?is)\\b(?:`?transaction`?|t)\\s*\\.\\s*user_id\\b");

    /**
     * Matches category = '<value>' with optional transaction/t alias/backticks.
     * Captures the category value (with doubled quotes intact) as group(1).
     */
    // Match category = <value> with optional table alias/backticks. <value> can be unquoted and may contain spaces/underscores/hyphens.
    public static final Pattern SQL_CATEGORY_EQUALS_PATTERN = Pattern.compile(
            "(?is)\\b(?:(?:`?transaction`?|t)\\s*\\.\\s*)?category\\s*=\\s*([A-Za-z0-9 _-]+)"
    );

    /**
     * Matches: FROM `transaction` <alias> (backticks optional). Captures the alias as group(1).
     */
    public static final Pattern SQL_FROM_TRANSACTION_ALIAS_PATTERN =
            Pattern.compile("(?is)\\bfrom\\s+`?transaction`?\\s+([a-zA-Z]\\w*)");

    /**
     * Detects any bare `transaction`.<col> reference (instead of using an alias like t.<col>).
     */
    public static final Pattern SQL_BARE_TRANSACTION_DOT_PATTERN =
            Pattern.compile("(?is)\\b`?transaction`?\\s*\\.");

    /**
     * Matches MONTH(time_stamp)=<n> with optional table prefix (transaction./`transaction`./t.).
     * Captures the numeric month as group(1).
     */
    public static final Pattern SQL_MONTH_EQUALS_PATTERN = Pattern.compile(
            "(?is)\\bmonth\\s*\\(\\s*(?:(?:`?transaction`?|`?t`?|t)\\s*\\.)?\\s*`?time_stamp`?\\s*\\)\\s*=\\s*(\\d{1,2})"
    );

    /**
     * Matches MONTH(time_stamp) IN (<list>) regardless of spacing/newlines.
     * Captures the comma-separated list inside the parentheses as group(1).
     */
    public static final Pattern SQL_MONTH_IN_PATTERN = Pattern.compile(
            "(?is)month\\s*\\(.*?time_stamp.*?\\)\\s*in\\s*\\(([^)]*)\\)"
    );

    /**
     * Matches YEAR(time_stamp)=<yyyy> with optional table prefix (transaction./`transaction`./t.).
     * Captures the numeric year as group(1).
     */
    public static final Pattern SQL_YEAR_EQUALS_PATTERN = Pattern.compile(
            "(?is)\\byear\\s*\\(\\s*(?:(?:`?transaction`?|`?t`?|t)\\s*\\.)?\\s*`?time_stamp`?\\s*\\)\\s*=\\s*(\\d{4})"
    );

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
            " offset ",
            // DCL / unsafe keywords (also catches weird model typos like "GRANT BY" instead of "GROUP BY")
            " grant ",
            " revoke "
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