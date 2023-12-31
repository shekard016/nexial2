/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 	http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package org.nexial.core.plugins.db;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.nexial.core.utils.ConsoleUtils;

import static org.apache.commons.lang3.builder.ToStringStyle.SIMPLE_STYLE;
import static org.nexial.core.NexialConst.Rdbms.*;

/**
 * Encapsulation of a SQL component, which consists of:
 * <ol>
 * <li>original - as provided to the constructor of this class</li>
 * <li>type - {@link Type}</li>
 * <li>comment - if found in the 'original' SQL</li>
 * <li>varName - any Nexial-specific comment that signified as a variable for the result of the associated SQL</li>
 * <li>sql - parsed version, which collapsed all line breaks and removed all comments</li>
 * </ol>
 * <p>
 * To qualify as a variable reference, the comment should look something like this:
 * <pre>
 *  -- nexial:my_variable
 * </pre>
 * <p>
 * Example: suppose this is the SQL string used ('original'):
 * <pre>
 * -- get all the office locations greater than 15743 (all in Seattle)
 * -- nexial:office locations
 * SELECT street_1, street_2, city, state, zip_code
 * FROM offices WHERE id > 15743;
 * </pre>
 * <p>
 * After parsing, the associated {@literal SqlComponent} instance would contain the following properties:
 * <ul>
 * <li>original - <br/><code>
 * -- get all the office locations greater than 15743 (all in Seattle)<br/>
 * -- nexial:office locations<br/>
 * SELECT street_1, street_2, city, state, zip_code<br/>
 * FROM offices WHERE id > 15743; </code></li>
 * <li>type - {@link Type#SELECT}</li>
 * <li>comments - <br/><code>
 * -- get all the office locations greater than 15743 (all in Seattle)<br/>
 * -- nexial:office locations </code></li>
 * <li>varName - <code>office locations</code></li>
 * <li>sql - <code>SELECT street_1, street_2, city, state, zip_code FROM offices WHERE id > 15743</code></li>
 * </ul>
 */
public class SqlComponent implements Serializable {
    private final String original;
    private Type type;
    private String comments;
    private String varName;
    private String sql;

    public enum Type {
        SELECT, UPDATE, INSERT, DELETE, COMMIT, ROLLBACK, CALL, WITH, CREATE, DROP, VACUUM, UNKNOWN, EXEC;

        public boolean hasResultset() { return this == SELECT || this == WITH; }

        public boolean isCommit() { return this == COMMIT; }

        public boolean isRollback() { return this == ROLLBACK; }

        public boolean isStoredProcedure() { return this == CALL || this == EXEC; }

        public boolean isUpdate() {
            return this == UPDATE ||
                   this == INSERT ||
                   this == DELETE ||
                   this == CREATE ||
                   this == DROP;
        }

        public static Type resolveFromSql(String sql) {
            if (StringUtils.isBlank(sql)) { return null; }

            String sqlStart = StringUtils.trim(StringUtils.removeStartIgnoreCase(sql, "{"));
            sqlStart = StringUtils.trim(StringUtils.substringBefore(sqlStart, " "));
            sqlStart = StringUtils.upperCase(sqlStart);
            if (StringUtils.isBlank(sqlStart)) { return null; }

            try {
                return Type.valueOf(StringUtils.upperCase(sqlStart));
            } catch (IllegalArgumentException e) {
                ConsoleUtils.log("Unknown SQL type from SQL: '" + sql + "'");
                return UNKNOWN;
            }
        }
    }

    public SqlComponent(String original) {
        this.original = original;
        parse();
    }

    public static List<SqlComponent> toList(String sqlText) {
        List<SqlComponent> list = new ArrayList<>();
        if (StringUtils.isBlank(sqlText)) { return list; }

        String[] sqlStatements = StringUtils.split(sqlText, SQL_DELIM);
        for (String sqlStatement : sqlStatements) {
            if (StringUtils.isBlank(sqlStatement)) { continue; }
            list.add(new SqlComponent(sqlStatement));
        }

        return list;
    }

    public String getOriginal() { return original; }

    public Type getType() { return type; }

    public String getComments() { return comments; }

    public String getVarName() { return varName; }

    public String getSql() { return sql; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, SIMPLE_STYLE).append("varName", varName).append("sql", sql).toString();
    }

    private void parse() {
        StringBuilder commentBuffer = new StringBuilder();
        StringBuilder sqlBuffer = new StringBuilder();

        String[] lines = StringUtils.split(original, SQL_LINE_SEP);
        for (String line : lines) {
            // has comment?
            if (StringUtils.equals(StringUtils.trim(line), SQL_COMMENT)) { continue; }
            String lineComment = StringUtils.trim(StringUtils.substringAfter(line, SQL_COMMENT));
            if (StringUtils.isNotBlank(lineComment)) {
                String varName = StringUtils.substringAfter(lineComment, SQL_VAR);
                if (StringUtils.isNotBlank(varName)) { this.varName = StringUtils.trim(varName);}

                commentBuffer.append(lineComment).append(SQL_LINE_SEP);
                sqlBuffer.append(StringUtils.trim(StringUtils.substringBefore(line, SQL_COMMENT))).append(" ");
            } else {
                sqlBuffer.append(StringUtils.trim(line)).append(" ");
            }
        }

        comments = StringUtils.trim(commentBuffer.toString());
        sql = StringUtils.trim(sqlBuffer.toString());
        sql = StringUtils.removeEnd(sql, ";");
        type = Type.resolveFromSql(sql);
    }
}
