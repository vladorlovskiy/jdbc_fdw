/* contrib/jdbc_fdw/jdbc_fdw--1.1.sql */

-- complain if script is sourced in psql, rather than via CREATE EXTENSION
\echo Use "CREATE EXTENSION jdbc_fdw" to load this file. \quit

CREATE OR REPLACE FUNCTION jdbc_fdw_version()
RETURNS pg_catalog.int4 STRICT
AS 'MODULE_PATHNAME' LANGUAGE C;

CREATE FUNCTION jdbc_exec (text, text)
RETURNS setof record
AS 'MODULE_PATHNAME','jdbc_exec'
LANGUAGE C STRICT PARALLEL RESTRICTED;

CREATE FUNCTION jdbc_fdw_handler()
RETURNS fdw_handler
AS 'MODULE_PATHNAME'
LANGUAGE C STRICT;

CREATE FUNCTION jdbc_fdw_validator(text[], oid)
RETURNS void
AS 'MODULE_PATHNAME'
LANGUAGE C STRICT;

CREATE FOREIGN DATA WRAPPER jdbc_fdw
  HANDLER jdbc_fdw_handler
  VALIDATOR jdbc_fdw_validator;

CREATE FUNCTION jdbc_get_catalogs (
  foregn_server_name text,
  out table_cat text
 )
RETURNS setof text
AS 'MODULE_PATHNAME','jdbc_get_catalogs'
LANGUAGE C IMMUTABLE PARALLEL RESTRICTED;

CREATE FUNCTION jdbc_get_schemas (
  foregn_server_name text,
  catalog_name text,
  schema_pattern text,
  out table_cat text,
  out table_schem text
 )
RETURNS setof record
AS 'MODULE_PATHNAME','jdbc_get_schemas'
LANGUAGE C IMMUTABLE PARALLEL RESTRICTED;

CREATE FUNCTION jdbc_get_tables (
  foregn_server_name text,
  catalog_name text,
  schema_pattern text,
  table_pattern text,
  table_type_csv text,
  out table_cat text,
  out table_schem text,
  out table_name text,
  out table_type text,
  out remarks text,
  out type_cat text,
  out type_schem text,
  out type_name text,
  out self_referencing_col_name text,
  out ref_generation text
 )
RETURNS setof record
AS 'MODULE_PATHNAME','jdbc_get_tables'
LANGUAGE C IMMUTABLE PARALLEL RESTRICTED;

CREATE FUNCTION jdbc_get_columns (
  foregn_server_name text,
  catalog_name text,
  schema_pattern text,
  table_pattern text,
  column_name text,
  out TABLE_CAT text,
  out TABLE_SCHEM text,
  out TABLE_NAME text,
  out COLUMN_NAME text,
  out DATA_TYPE int,
  out TYPE_NAME text,
  out COLUMN_SIZE int,
  out BUFFER_LENGTH int,
  out DECIMAL_DIGITS int,
  out NUM_PREC_RADIX int,
  out NULLABLE int,
  out REMARKS text,
  out COLUMN_DEF text,
  out SQL_DATA_TYPE int,
  out SQL_DATETIME_SUB int,
  out CHAR_OCTET_LENGTH int,
  out ORDINAL_POSITION int,
  out IS_NULLABLE text,
  out SCOPE_CATLOG text,
  out SCOPE_SCHEMA text,
  out SCOPE_TABLE text,
  out SOURCE_DATA_TYPE int,
  out IS_AUTOINCREMENT text,
  out IS_GENERATEDCOLUMN text
)
RETURNS setof record
AS 'MODULE_PATHNAME','jdbc_get_columns'
LANGUAGE C IMMUTABLE PARALLEL RESTRICTED;