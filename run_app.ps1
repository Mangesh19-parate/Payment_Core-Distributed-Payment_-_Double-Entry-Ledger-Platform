$ErrorActionPreference = "Stop"

$env:JAVA_HOME = "D:\Java\Java\jdk-24"
$env:PATH = "D:\Java\Java\jdk-24\bin;C:\Users\HP\AppData\Local\Programs\apache-maven-3.9.9\bin;$env:PATH"

Write-Host "Compiling test-classes with Maven..."
mvn test-compile -DskipTests

Write-Host "Launching PaymentCore DevServerLauncher with embedded infrastructure..."
mvn exec:java "-Dexec.mainClass=com.platform.DevServerLauncher" "-Dexec.classpathScope=test"
