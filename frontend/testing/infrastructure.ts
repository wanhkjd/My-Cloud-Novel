/** Only dedicated test credentials are accepted; never derive a target from the runtime DB_URL. */
export function e2eEnvironment(input: Record<string, string | undefined>) {
  // JVM options / external configuration can outrank the explicit test environment below.
  // Refuse these overrides before starting a server that would later delete test fixtures.
  const overrides = [
    'JAVA_TOOL_OPTIONS',
    'JDK_JAVA_OPTIONS',
    '_JAVA_OPTIONS',
    'SPRING_APPLICATION_JSON',
    'SPRING_CONFIG_LOCATION',
    'SPRING_CONFIG_ADDITIONAL_LOCATION',
    'SPRING_CONFIG_IMPORT',
    'SPRING_CONFIG_NAME',
    'SPRING_PROFILES_ACTIVE',
    'SPRING_PROFILES_INCLUDE',
    'SPRING_DATASOURCE_JNDI_NAME',
  ];
  for (const name of overrides) {
    if (input[name]?.trim()) {
      throw new Error('Unset ' + name + ' before running isolated browser tests.');
    }
  }
  const host = input.TEST_MYSQL_HOST ?? '127.0.0.1';
  if (![host].every((value) => /^[A-Za-z0-9.-]+$/.test(value))) {
    throw new Error('Test hosts must be hostnames or IPv4 addresses, not connection URLs.');
  }
  const port = (value: string) => {
    if (!/^\d+$/.test(value) || Number(value) < 1 || Number(value) > 65535) {
      throw new Error('Test ports must be between 1 and 65535.');
    }
    return value;
  };
  const username = input.E2E_DB_USERNAME ?? 'cloud_novel_e2e';
  if (username !== 'cloud_novel_e2e' || !input.E2E_DB_PASSWORD?.trim()) {
    throw new Error(
      'Configure the dedicated cloud_novel_e2e account and E2E_DB_PASSWORD in .env.test.',
    );
  }
  const url =
    'jdbc:mysql://' +
    host +
    ':' +
    port(input.TEST_MYSQL_PORT ?? '3306') +
    '/cloud_novel_e2e?characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&sslMode=DISABLED&allowPublicKeyRetrieval=true';
  return {
    SERVER_ADDRESS: '127.0.0.1',
    SERVER_PORT: '18080',
    ADMIN_USERNAME: 'admin',
    ADMIN_PASSWORD: 'isolated-e2e-test-password-only',
    APP_ADMIN_USERNAME: 'admin',
    APP_ADMIN_PASSWORD: 'isolated-e2e-test-password-only',
    DB_URL: url,
    DB_USERNAME: username,
    DB_PASSWORD: input.E2E_DB_PASSWORD,
    SPRING_DATASOURCE_URL: url,
    SPRING_DATASOURCE_USERNAME: username,
    SPRING_DATASOURCE_PASSWORD: input.E2E_DB_PASSWORD,
    SPRING_DATASOURCE_DRIVER_CLASS_NAME: 'com.mysql.cj.jdbc.Driver',
    SPRING_SQL_INIT_MODE: 'never',
    BOOK_STORAGE: './target/e2e-books',
    APP_STORAGE_DIRECTORY: './target/e2e-books',
    COOKIE_SECURE: 'false',
    SERVER_SERVLET_SESSION_COOKIE_SECURE: 'false',
  };
}
