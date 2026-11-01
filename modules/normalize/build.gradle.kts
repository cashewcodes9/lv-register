// Pure functions: lake rows (column name -> text) to domain records, with counted drops and
// flags. No I/O; the cli feeds it rows from the lake reader.
plugins { id("lv.java-conventions") }

dependencies {
    api(project(":domain"))
}
