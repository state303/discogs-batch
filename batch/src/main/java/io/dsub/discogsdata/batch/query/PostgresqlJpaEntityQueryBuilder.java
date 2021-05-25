package io.dsub.discogsdata.batch.query;

import io.dsub.discogsdata.common.entity.base.BaseEntity;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;

// todo: impl aop applied test
// todo: unit test
public class PostgresqlJpaEntityQueryBuilder implements JpaEntityQueryBuilder<BaseEntity> {

  public static final String INSERT_QUERY_FORMAT =
      "INSERT INTO %s(%s) VALUES (%s)";

  public static final String UPSERT_QUERY_FORMAT = "ON CONFLICT (%s) DO UPDATE SET %s WHERE %s";

  @Override
  public String getInsertQuery(Class<? extends BaseEntity> targetClass) {
    boolean idInclusive = !isIdAutoGenerated(targetClass);
    Map<String, String> mappings = getMappings(targetClass, idInclusive);
    String tblName = getTableName(targetClass);
    List<String> columns = new ArrayList<>(mappings.keySet());
    List<String> values = new ArrayList<>(mappings.values());

    Field createdAt = getCreatedAtField(targetClass);
    Field lastModified = getLastModifiedField(targetClass);

    for (int i = 0; i < values.size(); i++) {
      String origin = values.get(i);
      if (createdAt != null && origin.equals(createdAt.getName())) {
        values.set(i, "NOW()");
      } else if (lastModified != null && origin.equals(lastModified.getName())) {
        values.set(i, "NOW()");
      } else {
        values.set(i, COLON + origin);
      }
    }

    return String.format(
        INSERT_QUERY_FORMAT,
        tblName,
        String.join(",", columns),
        String.join(",", values));
  }

  @Override
  public String getUpsertQuery(Class<? extends BaseEntity> targetClass) {
    String insertClause = getInsertQuery(targetClass) + SPACE;
    boolean idAutoGenerated = isIdAutoGenerated(targetClass);
    boolean hasUniqueConstraints = hasUniqueConstraints(targetClass);
    if (idAutoGenerated && !hasUniqueConstraints) {
      return insertClause;
    }
    return insertClause + String.format(
        UPSERT_QUERY_FORMAT,
        getOnConflictCols(targetClass),
        getUpdateClause(targetClass),
        getWhereClause(targetClass));
  }

  private String getWhereClause(Class<? extends BaseEntity> targetClass) {
    boolean idAutoGenerated = isIdAutoGenerated(targetClass);
    boolean hasUniqueConstraints = hasUniqueConstraints(targetClass);
    if (idAutoGenerated && hasUniqueConstraints) {
      return getUniqueConstraintColumns(targetClass).entrySet().stream()
          .map(this::wrapColumnFieldByEquals)
          .collect(Collectors.joining(SPACE + AND + SPACE));
    }

    if (!idAutoGenerated && hasUniqueConstraints) {
      Map<String, String> colFieldMap = getUniqueConstraintColumns(targetClass);
      colFieldMap.putAll(getIdMappings(targetClass));
      return colFieldMap.entrySet().stream()
          .map(this::wrapColumnFieldByEquals)
          .collect(Collectors.joining(SPACE + AND + SPACE));
    }

    return getIdMappings(targetClass).entrySet().stream()
        .map(this::wrapColumnFieldByEquals)
        .collect(Collectors.joining(SPACE + AND + SPACE));
  }

  private String getOnConflictCols(Class<? extends BaseEntity> targetClass) {
    boolean idAutoGenerated = isIdAutoGenerated(targetClass);
    boolean hasUniqueConstraints = hasUniqueConstraints(targetClass);
    if (idAutoGenerated && hasUniqueConstraints) {
      return String.join(",", getUniqueConstraintColumns(targetClass).keySet());
    }
    if (!idAutoGenerated && hasUniqueConstraints) {
      Set<String> cols = new HashSet<>(getUniqueConstraintColumns(targetClass).keySet());
      cols.addAll(getIdMappings(targetClass).keySet());
      return String.join(",", cols);
    }
    return String.join(",", getIdMappings(targetClass).keySet());
  }

  private String wrapColumnFieldByEquals(Entry<String, String> entry) {
    return entry.getKey() + EQUALS + COLON + entry.getValue();
  }

  private String getUpdateClause(Class<? extends BaseEntity> targetClass) {
    boolean idAutoGenerated = isIdAutoGenerated(targetClass);
    boolean hasUniqueConstraints = hasUniqueConstraints(targetClass);
    Field lastModified = getLastModifiedField(targetClass);
    Field createdAt = getCreatedAtField(targetClass);
    if (idAutoGenerated && hasUniqueConstraints) {
      return getMappingsOutsideUniqueConstraints(targetClass, false).entrySet().stream()
          .filter(entry -> createdAt == null || !entry.getValue().contains(createdAt.getName()))
          .map(entry -> makeUpdateSetMapping(entry.getKey(), entry.getValue(), lastModified))
          .collect(Collectors.joining(SPACE));
    }
    return getMappings(targetClass, false).entrySet().stream()
        .filter(entry -> createdAt == null || !entry.getValue().contains(createdAt.getName()))
        .map(entry -> makeUpdateSetMapping(entry.getKey(), entry.getValue(), lastModified))
        .collect(Collectors.joining(SPACE));
  }

  private String makeUpdateSetMapping(String key, String value, Field lastUpdateAt) {
    if (lastUpdateAt != null && value.equals(lastUpdateAt.getName())) {
      return key + EQUALS + "NOW()";
    }
    return key + EQUALS + COLON + value;
  }
}
