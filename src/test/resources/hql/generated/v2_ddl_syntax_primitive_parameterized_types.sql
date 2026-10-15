-- DDL 类型声明保留每个 primitive 名称，以及 DECIMAL/CHAR/VARCHAR 的参数。
CREATE TABLE dwd.ddl_primitive_types (
    flag BOOLEAN,
    tiny TINYINT,
    short_value SMALLINT,
    int_value INT,
    long_value BIGINT,
    float_value FLOAT,
    double_value DOUBLE,
    amount DECIMAL(18,4),
    fixed_text CHAR(8),
    variable_text VARCHAR(64),
    text_value STRING,
    binary_value BINARY,
    timestamp_value TIMESTAMP,
    date_value DATE
);
