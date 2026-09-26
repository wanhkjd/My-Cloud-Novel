import { describe, expect, it } from 'vitest';
import { e2eEnvironment } from './infrastructure';

describe('real MySQL E2E isolation', () => {
  it('never inherits runtime database or private original paths', () => {
    const config = e2eEnvironment({
      E2E_DB_PASSWORD: 'synthetic-e2e-only',
      DB_URL: 'jdbc:mysql://production/cloud_novel',
      DB_USERNAME: 'root',
      DB_PASSWORD: 'runtime-secret',
      SPRING_DATASOURCE_URL: 'jdbc:mysql://production/cloud_novel',
      BOOK_STORAGE: '/real-private-books',
      APP_STORAGE_DIRECTORY: '/real-private-books',
    });
    expect(config.DB_URL).toContain('/cloud_novel_e2e?');
    expect(config.SPRING_DATASOURCE_URL).toBe(config.DB_URL);
    expect(config.DB_USERNAME).toBe('cloud_novel_e2e');
    expect(config.DB_PASSWORD).toBe('synthetic-e2e-only');
    expect(config.SPRING_SQL_INIT_MODE).toBe('never');
    expect(config.APP_STORAGE_DIRECTORY).toBe('./target/e2e-books');
  });
  it('fails without dedicated credentials rather than skipping tests', () => {
    expect(() => e2eEnvironment({ DB_PASSWORD: 'not-a-test-password' })).toThrow('E2E_DB_PASSWORD');
    expect(() =>
      e2eEnvironment({ E2E_DB_USERNAME: 'root', E2E_DB_PASSWORD: 'synthetic' }),
    ).toThrow();
  });
  it('rejects host injection and invalid test ports', () => {
    for (const input of [
      { TEST_MYSQL_HOST: '127.0.0.1/cloud_novel?x=1' },
      { TEST_MYSQL_PORT: '0' },
    ]) {
      expect(() => e2eEnvironment({ ...input, E2E_DB_PASSWORD: 'synthetic' })).toThrow();
    }
  });
  it('rejects higher-priority JVM and external configuration overrides', () => {
    for (const name of [
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
    ]) {
      expect(() => e2eEnvironment({ E2E_DB_PASSWORD: 'synthetic', [name]: 'override' })).toThrow(
        'Unset ' + name,
      );
    }
  });
});
