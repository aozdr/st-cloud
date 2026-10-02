import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

/** 本地 MySQL CLI 缺失时的 JDBC 桥接；凭据仅从环境读取，不写入命令行或日志。 */
class MysqlJdbc {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("需要一个 SQL 文本或 @SQL文件参数");
        String host = System.getenv().getOrDefault("STCLOUD_TEST_MYSQL_HOST", "127.0.0.1");
        String port = System.getenv().getOrDefault("STCLOUD_TEST_MYSQL_PORT", "3306");
        String database = System.getenv().getOrDefault("STCLOUD_TEST_MYSQL_DATABASE", "stcloud");
        String user = System.getenv().getOrDefault("STCLOUD_TEST_MYSQL_USER", "root");
        String password = System.getenv("MYSQL_PWD");
        if (password == null) password = System.getenv("STCLOUD_TEST_MYSQL_PASSWORD");
        if (password == null) throw new IllegalArgumentException("缺少 MYSQL_PWD 或 STCLOUD_TEST_MYSQL_PASSWORD");
        Class.forName("com.mysql.cj.jdbc.Driver");
        String sql = args[0].startsWith("@") ? Files.readString(Path.of(args[0].substring(1)), StandardCharsets.UTF_8) : args[0];
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));
        try (Connection connection = DriverManager.getConnection("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=true&connectTimeout=5000&socketTimeout=30000", user, password);
             Statement statement = connection.createStatement()) {
            boolean result = statement.execute(sql);
            do {
                if (result) try (ResultSet rows = statement.getResultSet()) {
                    ResultSetMetaData meta = rows.getMetaData();
                    for (int i=1; i<=meta.getColumnCount(); i++) System.out.print((i==1 ? "" : "\t") + meta.getColumnLabel(i));
                    System.out.println();
                    while (rows.next()) {
                        for (int i=1; i<=meta.getColumnCount(); i++) {
                            String value = rows.getString(i);
                            System.out.print((i==1 ? "" : "\t") + (value == null ? "NULL" : value.replace("\t", " ").replace("\n", " ").replace("\r", " ")));
                        }
                        System.out.println();
                    }
                }
                result = statement.getMoreResults();
            } while (result || statement.getUpdateCount() != -1);
        }
    }
}
