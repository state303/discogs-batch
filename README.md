# Discogs Batch

[![codecov](https://codecov.io/gh/state303/discogs-batch/branch/master/graph/badge.svg?token=SKVQUX2TKB)](https://codecov.io/gh/state303/discogs-batch)
[![Build Status](https://www.travis-ci.com/state303/discogs-batch.svg?branch=master)](https://www.travis-ci.com/state303/discogs-batch)


### ANNOUNCEMENT ⛑
Major reconstruction incoming.

Updated ERD: [Click Here](https://dbdocs.io/state303/OpenDiscogs)

### ABOUT THIS PROJECT

The aim of the project is to replicate the entire dump data set given
from [data.discogs.com](https://data.discogs.com).

In summary, the batch operates as following:

    - Currently only supports postgresql for higher stability and maintainability
    - One shot process with validations before firing jobs
    - Idempotent actions; may run several times for same source without issue
    - Supports dockerize, docker run with predefined batch commands

### Built With

[Spring-Batch](https://spring.io/projects/spring-batch)

[Liquibase](https://www.liquibase.org)

[JOOQ](https://www.jooq.org)

[ProgressBar](https://github.com/ctongfei/progressbar)

## Batch Commands

Commands will be accepted regardless of -- mark <b>ONLY IF</b> gets arguments directly from jar
file. Also, there is no impact from giving arguments in certain order. However, it will NOT accept
any duplicated arguments.

i.e. --m will work, as well as -m, m will.

Brief summary for the commands are as below...

|    NAME    | SYNONYM  |      REQUIRED         | MIN | MAX | FORMAT    | DEFAULT |  NOTE |
|------------|----------|-----------------------|-----|-----|-----------|---------|-------|
| username   | user, u  | :heavy_check_mark:    | 1   | 1   | STRING    | NULL                                |
| password   | pass, p  | :heavy_check_mark:    | 1   | 1   | STRING    | NULL                                |
| url        |          | :heavy_check_mark:    | 1   | 1   | addr:port | jdbc:postgresql://localhost:5432/discogs |
| type       | t        | :black_square_button: | 1   | 4   | a,b,...   | ARTIST, MEMBER, LABEL, RELEASE_ITEM                     |
| chunk_size | chunk, c | :black_square_button: | 1   | 1   | 0 < N     | 3000    |
| core_count | core     | :black_square_button: | 1   | 1   | 0 < N     | 80% of core from runtime |
| year       | y        | :black_square_button: | 1   | 1   | yyyy      | CURRENT | this or year_month.
| year_month | ym       | :black_square_button: | 1   | 1   | yyyy-mm   | CURRENT | this or year.
| etag       | e        | :black_square_button: | 1   | 4   | a,b,...   | MOST_RECENT | overrides type, date.
| mount      | m        | :black_square_button: | 0   | 0   | NONE      | -       | keep dump file
| strict     | s        | :black_square_button: | 0   | 0   | NONE      | -       | only perform specified type or ETag

### Required Arguments

It is important to note that there are three required arguments.

##### username

Username of the target database server. This will automatically be encoded to UTF-8. The user must
have sufficient permissions to create and modify the given schema or database.

##### password

Password for the username given. This will automatically be encoded to UTF-8.

##### url

URL for the target database. The expected releaseFormat for the url would be...

```text
--url=jdbc://postgresql://{server_address}:{port}/{target_database}
```

If you prefer to use specific database, please make sure to set it to the db prior to run batch,
otherwise the process will fail with messages.

if target_database is missing, will be set to discogs as default.

It is important to note that if given schema or database is empty, this batch will automatically
create tables via liquibase and sql.

### Year, Year Month, Type and ETag.

First and foremost, by specifying the ETag, any arguments given for year, year-month, type will be
ignored. This is intended behavior as each dump relies on other dump types in specified year and
month.

Other than ETag, it is important to note that providing both year and year-month at the same time is
not supported.

Finally, types cannot be duplicated.

If you specify a year and a type for example, batch will automatically fetch and process the target
dump INCLUDING the dependant dump.

### Dependencies

Dump dependency for other type are can be described as following:

|    TYPE   |       REQUIRES        |
|-----------|-----------------------|
| ARTIST    | -                     |
| LABEL     | -                     |
| MASTER    | ARTIST, LABEL         |
| RELEASE   | ARTIST, LABEL, MASTER |

The job will always be executed by order as following:

```text
ARTIST > LABEL > MASTER > RELEASE
```

If you run the batch with following arguments:
> url=[?] user=[?] pass=[?] year-month=2021-3 type=release

Batch will be executed with artist, label, master, release dumps from 2021, March.

If you do not specify any options, but simply call the batch by username, password and url, then
batch will be executed with most recent artist, label, master, release dumps.

### Mount and Strict

##### Mount

If mount option is specified, the downloaded file from the discogs data will not be removed. This
maybe useful if you need to keep the downloaded dump.

##### Strict

This option will not resolve any dependency, but to simply execute with given etag or type.

### Concurrency

The application will automatically resolve the current core size of running system (currently 80%).
If core count argument will override the default setting, and validate the value accordingly.

The core count cannot exceed 80% of full core size of given machine, thus setting the value above
will simply be ignored.

Also, setting core count as negative value will also ignore the setting, which will simply set the
core count to default(80%).

### Chunk Size

The default chunk-size is 500, however, in average environment, I would recommend to set to 100~200. This is totally up to the I/O spec and postgres settings of the running client and database server, so feel free to experiment with it.
