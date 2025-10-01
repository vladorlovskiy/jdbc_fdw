/*
 * --------------------------------------------- jq.h Low level JDBC based
 * functions replacing the libpq-fe functions
 *
 * Heimir Sverrisson, 2015-04-13
 *
 * Portions Copyright (c) 2021, TOSHIBA CORPORATION
 *
 * ---------------------------------------------
 */
#ifndef JQ_H
#define JQ_H

#include "libpq-fe.h"
#include "jni.h"
#include "executor/tuptable.h"
#include "nodes/execnodes.h"

typedef struct jdbcFdwExecutionState
{
	char	   *query;
	int			NumberOfRows;
}			jdbcFdwExecutionState;

/* JDBC connection, same role as PGconn */
typedef struct JDBCUtilsInfo
{
	jobject		JDBCUtilsObject;
	ConnStatusType status;
	jdbcFdwExecutionState *festate;
	char	   *q_char;
}			JDBCUtilsInfo;

/* result status from JDBC */
typedef ExecStatusType Jresult;

/* JDBC columnInfo */
typedef struct JcolumnInfo
{
	char	   *column_name;
	char	   *column_type;
	bool		primary_key;
}			JcolumnInfo;

/* JDBC tableInfo */
typedef struct JtableInfo
{
	char	   *table_name;
	List	   *column_info;
}			JtableInfo;

/*
 * Replacement for libpq-fe.h functions
 */
extern Jresult * jq_exec(JDBCUtilsInfo * jdbcUtilsInfo, const char *query);
extern Jresult * jq_exec_id(JDBCUtilsInfo * jdbcUtilsInfo, const char *query, int *resultSetID);
extern void *jq_release_resultset_id(JDBCUtilsInfo * jdbcUtilsInfo, int resultSetID);
extern Jresult * jq_exec_prepared(JDBCUtilsInfo * jdbcUtilsInfo, int resultSetID);

extern void jq_clear(Jresult * res);
extern char *jq_cmd_tuples(Jresult * res);
extern char *jq_get_value(const Jresult * res, int tup_num, int field_num);
extern Jresult * jq_prepare(JDBCUtilsInfo * jdbcUtilsInfo, const char *query,
							const Oid *paramTypes, int *resultSetID);
extern int	jq_nfields(const Jresult * res);
extern int	jq_get_is_null(const Jresult * res, int tup_num, int field_num);
extern JDBCUtilsInfo * jq_connect_db_params(const ForeignServer *server, const UserMapping *user, const char *const *keywords,
											const char *const *values);
extern ConnStatusType jq_status(const JDBCUtilsInfo * jdbcUtilsInfo);
extern char *jq_error_message(const JDBCUtilsInfo * jdbcUtilsInfo);
extern void jq_finish(void);
extern int	jq_server_version(const JDBCUtilsInfo * jdbcUtilsInfo);
extern char *jq_result_error_field(const Jresult * res, int fieldcode);
extern PGTransactionStatusType jq_transaction_status(const JDBCUtilsInfo * jdbcUtilsInfo);
extern TupleTableSlot *jq_iterate(JDBCUtilsInfo * jdbcUtilsInfo, ForeignScanState *node, List *retrieved_attrs, int resultSetID);
extern void jq_iterate_all_row(FunctionCallInfo fcinfo, JDBCUtilsInfo * jdbcUtilsInfo, TupleDesc tupleDescriptor, int resultSetID);
extern List *jq_get_column_infos_without_key(JDBCUtilsInfo * jdbcUtilsInfo, int *resultSetID, int *column_num);
extern void *jq_bind_sql_var(JDBCUtilsInfo * jdbcUtilsInfo, Oid type, int attnum, Datum value, bool *isnull, int resultSetID);
extern Datum jdbc_convert_to_pg(Oid pgtyp, int pgtypmod, char *value);
extern List *jq_get_schema_info(JDBCUtilsInfo * jdbcUtilsInfo);
extern void jdbc_jvm_init(const ForeignServer *server, const UserMapping *user);
extern void jq_cancel(JDBCUtilsInfo * jdbcUtilsInfo);
void		jq_inval_callback(int cacheid, uint32 hashvalue);
void		jq_release_all_result_sets(void);


extern Jresult * jq_get_catalogs(JDBCUtilsInfo * jdbcUtilsInfo,	int *resultSetID);

extern Jresult * jq_exec_update_prepared(JDBCUtilsInfo * jdbcUtilsInfo, const int *paramLengths,
	const int *paramFormats, int resultFormat, int resultSetID);
extern Jresult *jq_exec_query_prepared(JDBCUtilsInfo * jdbcUtilsInfo, int resultSetID);

extern int jq_get_number_of_affected_rows(JDBCUtilsInfo * jdbcUtilsInfo, int resultSetID);

extern Jresult * jq_get_schemas(JDBCUtilsInfo *jdbcUtilsInfo,
	const char *catalog,
	const char *schemapattern,
	int *resultSetID);
extern Jresult * jq_get_tables(JDBCUtilsInfo *jdbcUtilsInfo,
	const char *catalog,
	const char *schemapattern,
	const char *tablepattern,
	const char *tabletypes,
	int *resultSetID);
extern Jresult * jq_get_columns(JDBCUtilsInfo *jdbcUtilsInfo,
	const char *catalog,
	const char *schemapattern,
	const char *tablepattern,
	const char *columnpattern,
	int *resultSetID);
extern void jq_set_autocommit(JDBCUtilsInfo * jdbcUtilsInfo, bool autocommit);
extern bool jq_get_autocommit(JDBCUtilsInfo * jdbcUtilsInfo);

extern int jq_exec_update(JDBCUtilsInfo * jdbcUtilsInfo, const char *command);
extern void jq_snowflake_upload_to_stage(JDBCUtilsInfo * jdbcUtilsInfo,
	const char *stageName, const char *destPrefix,
	const char *data, const char *fileName,
	bool compress);


#endif							/* JQ_H */
