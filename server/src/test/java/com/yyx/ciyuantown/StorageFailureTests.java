package com.yyx.ciyuantown;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yyx.ciyuantown.common.exception.BizException;
import com.yyx.ciyuantown.common.storage.StorageService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StorageFailureTests {
    @TempDir Path root;
    @Test void missingMigrationReportsCauseBeforeWritingFile() throws Exception {
        JdbcTemplate db=mock(JdbcTemplate.class);
        when(db.update(anyString(),any(Object[].class))).thenThrow(new BadSqlGrammarException("insert","",new SQLException("missing table","42S02",1146)));
        var storage=new StorageService(db,new ObjectMapper());
        Path source=Files.writeString(root.resolve("source.txt"),"test");
        var config=new StorageService.Config("test","local",root.toString(),"","","","","","");
        var error=assertThrows(BizException.class,()->storage.put(config,"posts/file.txt",source,"text/plain"));
        assertTrue(error.getMessage().contains("P12"));
        assertFalse(Files.exists(root.resolve("posts/file.txt")));
    }
    @Test void failedRegistryInsertRemovesUploadedFile() throws Exception {
        JdbcTemplate db=mock(JdbcTemplate.class);
        when(db.update(anyString(),any(Object[].class))).thenReturn(1).thenThrow(new BadSqlGrammarException("insert","",new SQLException("denied","42000",1142)));
        var storage=new StorageService(db,new ObjectMapper());
        Path source=Files.writeString(root.resolve("source.txt"),"test");
        var config=new StorageService.Config("test","local",root.toString(),"","","","","","");
        var error=assertThrows(BizException.class,()->storage.put(config,"posts/file.txt",source,"text/plain"));
        assertTrue(error.getMessage().contains("权限"));
        assertFalse(Files.exists(root.resolve("posts/file.txt")));
        assertEquals("test",Files.readString(source));
    }
}
