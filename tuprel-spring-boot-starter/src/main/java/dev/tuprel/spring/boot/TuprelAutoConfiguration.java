package dev.tuprel.spring.boot;

import dev.tuprel.postgresql.PostgresqlDatabase;
import dev.tuprel.runtime.TuprelDatabase;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnSingleCandidate(DataSource.class)
class TuprelAutoConfiguration {
    @Bean
    @ConditionalOnMissingBean(TuprelDatabase.class)
    TuprelDatabase tuprelDatabase(DataSource dataSource) {
        return PostgresqlDatabase.using(dataSource);
    }
}
