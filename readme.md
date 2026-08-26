# Chạy ứng dụng giao diện JavaFX:
cd java
.\mvnw.cmd javafx:run

# Hoặc chạy kiểm thử tự động toàn hệ thống:
cd java
.\mvnw.cmd compile exec:java -Dexec.mainClass=com.company.office.SystemIntegrationTest
