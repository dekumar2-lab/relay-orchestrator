package com.relay.orchestrator.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Repository
public class IndexRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public IndexRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void clearRepoIndex(String repoId) {
        jdbc.update("DELETE FROM dependencies WHERE repo_id = ?", repoId);
        jdbc.update("DELETE FROM methods WHERE class_id IN (SELECT id FROM classes WHERE repo_id = ?)", repoId);
        jdbc.update("DELETE FROM classes WHERE repo_id = ?", repoId);
        // NEW: also clear chunks for this repo
        jdbc.update("DELETE FROM code_chunks WHERE repo_id = ?", repoId);
    }

    public long insertClass(String repoId, String packageName, String className, String filePath,
            String typeKind, List<String> annotations, String extendsType, List<String> implementsTypes) {
        try {
            String annosJson = mapper.writeValueAsString(annotations);
            String implsJson = mapper.writeValueAsString(implementsTypes);

            String sql = "INSERT INTO classes (repo_id, package_name, class_name, file_path, type_kind, annotations, extends_type, implements_types) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            jdbc.update(sql, repoId, packageName, className, filePath, typeKind, annosJson, extendsType, implsJson);

            return jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save class to database index", e);
        }
    }

    public void insertMethod(long classId, String methodName, String signature, String returnType,
            List<String> parameters, int lineStart, int lineEnd, List<String> callsOut) {
        try {
            String paramsJson = mapper.writeValueAsString(parameters);
            String callsJson = mapper.writeValueAsString(callsOut);

            String sql = "INSERT INTO methods (class_id, method_name, signature, return_type, parameters, line_start, line_end, calls_out) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            jdbc.update(sql, classId, methodName, signature, returnType, paramsJson, lineStart, lineEnd, callsJson);
        } catch (Exception e) {
            throw new RuntimeException("Failed to save method to database index", e);
        }
    }

    public void insertDependency(String repoId, long sourceClassId, String targetType, String dependencyKind,
            String targetFilePath) {
        String sql = "INSERT INTO dependencies (repo_id, source_class_id, target_type, dependency_kind, target_file_path) VALUES (?, ?, ?, ?, ?)";
        jdbc.update(sql, repoId, sourceClassId, targetType, dependencyKind, targetFilePath);
    }

    public List<Map<String, Object>> queryTopologyData() {
        String sql = "SELECT c.repo_id, c.package_name, c.class_name, c.id as class_id, " +
                "(SELECT COUNT(*) FROM methods m WHERE m.class_id = c.id) as method_count " +
                "FROM classes c ORDER BY c.repo_id, c.package_name, c.class_name";
        return jdbc.queryForList(sql);
    }

    public List<Map<String, Object>> queryMethodsForClass(long classId) {
        String sql = "SELECT id, method_name, signature, return_type, parameters, line_start " +
                "FROM methods WHERE class_id = ? ORDER BY line_start, method_name";
        return jdbc.queryForList(sql, classId);
    }
}
