package com.myjournal.android

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.myjournal.domain.*

@Composable
fun NotebookScreen(model: JournalViewModel) {
    val state by model.notebooks.state.collectAsState()
    var create by remember { mutableStateOf(false) }
    var editBook by remember { mutableStateOf(false) }
    var addPage by remember { mutableStateOf(false) }
    var manage by remember { mutableStateOf(false) }
    var editPage by remember { mutableStateOf<NotebookPage?>(null) }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    var toolName by rememberSaveable { mutableStateOf(EditorTool.PEN.name) }
    var color by rememberSaveable { mutableLongStateOf(0xFF252A30L) }
    var penWidth by rememberSaveable { mutableFloatStateOf(2.8f) }
    var markerWidth by rememberSaveable { mutableFloatStateOf(18f) }
    val tool = EditorTool.valueOf(toolName)
    var canvas by remember { mutableStateOf<JournalCanvas?>(null) }
    val book = state.notebook
    val page = state.page
    LaunchedEffect(book?.metadata?.id, page?.metadata?.id) {
        book?.let { model.rememberNotebookSelection(it.metadata.id, page?.metadata?.id) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(when { state.loading -> "Loading notebooks…"; state.error != null -> state.error!!
            state.pending > 0 -> "Saving notebook…"; else -> "Notebooks saved locally" }, style = MaterialTheme.typography.bodySmall)
        if (state.error != null) TextButton(onClick = model.notebooks::retry) { Text("Retry notebook") }
        if (book == null) {
            Row { Button(onClick = { create = true }) { Text("Create notebook") }
                FilterChip(selected = showArchived, onClick = { showArchived = !showArchived }, label = { Text("Show archived notebooks") }) }
            if (!state.loading && state.notebooks.isEmpty()) Text("Create your first notebook. Nothing is stored until you create one.")
            LazyColumn {
                items(state.notebooks.filter { showArchived || !it.archived }, key = { it.metadata.id.toString() }) { item ->
                    TextButton(onClick = { model.openNotebook(item.metadata.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(item.name + if (item.archived) " · Archived" else "")
                    }
                    item.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    HorizontalDivider()
                }
            }
        } else {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(onClick = { canvas?.finishInProgress(); model.openNotebook(null) }) { Text("Notebook list") }
                Text(book.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(8.dp))
                TextButton(onClick = { canvas?.finishInProgress(); editBook = true }) { Text("Edit notebook") }
                TextButton(onClick = { canvas?.finishInProgress(); model.notebooks.archiveNotebook(!book.archived) }) {
                    Text(if (book.archived) "Restore notebook" else "Archive notebook")
                }
            }
            if (book.archived) Text("Archived notebook. Restore it to edit; all pages and handwriting are retained.")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                val activePages = state.pages.filter { !it.archived }.sortedBy { it.order }
                val index = activePages.indexOfFirst { it.metadata.id == page?.metadata?.id }
                TextButton(enabled = index > 0 && !state.loading, onClick = { canvas?.finishInProgress(); model.notebooks.selectPage(activePages[index-1].metadata.id) }) { Text("Previous notebook page") }
                Text("Page ${if (index < 0) 0 else index+1} of ${activePages.size}", Modifier.padding(top = 16.dp))
                TextButton(enabled = index >= 0 && index+1 < activePages.size && !state.loading, onClick = { canvas?.finishInProgress(); model.notebooks.selectPage(activePages[index+1].metadata.id) }) { Text("Next notebook page") }
                TextButton(enabled = !book.archived && !state.loading, onClick = { canvas?.finishInProgress(); addPage = true }) { Text("Add notebook page") }
                TextButton(onClick = { canvas?.finishInProgress(); manage = true }) { Text("Manage pages") }
            }
            page?.title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            if (!book.archived) {
                SharedInkTools(tool,color,{ canvas?.finishInProgress();toolName=it.name },{ canvas?.finishInProgress();color=it },state.canUndo,state.canRedo,
                    { canvas?.finishInProgress();model.notebooks.undo() },{ canvas?.finishInProgress();model.notebooks.redo() })
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Text("Thickness", Modifier.padding(top=16.dp))
                    if (tool != EditorTool.ERASER) Slider(if (tool == EditorTool.PEN) penWidth else markerWidth,
                        { if (tool == EditorTool.PEN) penWidth=it else markerWidth=it },
                        valueRange=if (tool == EditorTool.PEN) 1f..8f else 8f..40f,modifier=Modifier.width(144.dp))
                    TextButton(onClick={ canvas?.resetViewport() }) { Text("Fit notebook width") }
                }
            }
            if (!state.loading && page != null && !page.archived && !book.archived) key(page.metadata.id) {
                AndroidView(factory={ context -> model.notebooks.retain(book.metadata.id,page.metadata.id); JournalCanvas(context).also { canvas=it } },
                    onReset=null,onRelease={ it.finishInProgress();model.notebooks.release(book.metadata.id,page.metadata.id) },
                    modifier=Modifier.weight(1f).fillMaxWidth(), update={ view -> canvas=view
                        view.bindSurface(page.surface,state.strokes,tool,color,if (tool==EditorTool.PEN) penWidth else markerWidth,
                            model.viewport(page.metadata.id),{ model.rememberViewport(page.metadata.id,it) },
                            { brush,argb,width,points -> model.notebooks.addStroke(brush,argb,width,points,book.metadata.id,page.metadata.id) },
                            { model.notebooks.erase(it,book.metadata.id,page.metadata.id) })
                    })
            } else Spacer(Modifier.weight(1f))
        }
    }
    if (create || editBook) {
        var name by remember { mutableStateOf(if (editBook) book?.name ?: "" else "") }
        var description by remember { mutableStateOf(if (editBook) book?.description ?: "" else "") }
        var template by remember { mutableStateOf(book?.defaultTemplate ?: PaperTemplate.RULED) }
        AlertDialog(onDismissRequest={create=false;editBook=false},title={Text(if(create) "Create notebook" else "Edit notebook")},text={
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name,{name=it},label={Text("Notebook name")},singleLine=true)
                OutlinedTextField(description,{description=it},label={Text("Description (optional)")})
                if(create) TemplateChoices(template){template=it}
            }
        },confirmButton={TextButton(enabled=name.trim().length in 1..120 && description.length<=2000,onClick={
            if(create) model.notebooks.create(name,description.takeIf{it.isNotBlank()},template)
            else model.notebooks.rename(name,description.takeIf{it.isNotBlank()})
            create=false;editBook=false
        }){Text(if(create) "Create" else "Apply notebook changes")}},dismissButton={TextButton(onClick={create=false;editBook=false}){Text("Cancel")}})
    }
    if(addPage || editPage != null) {
        val old=editPage
        var title by remember { mutableStateOf(old?.title ?: "") }
        var template by remember { mutableStateOf(old?.paper ?: book?.defaultTemplate ?: PaperTemplate.RULED) }
        AlertDialog(onDismissRequest={addPage=false;editPage=null},title={Text(if(addPage) "New notebook page" else "Edit page")},text={
            Column { OutlinedTextField(title,{title=it},label={Text("Page title (optional)")},singleLine=true);TemplateChoices(template){template=it}
                Text("Changing paper keeps all handwriting coordinates.",style=MaterialTheme.typography.bodySmall) }
        },confirmButton={TextButton(enabled=title.length<=120,onClick={
            if(addPage) model.notebooks.addPage(template,title.takeIf{it.isNotBlank()})
            else old?.let{model.notebooks.editPage(it.metadata.id,title.takeIf{it.isNotBlank()},template,it.archived)}
            addPage=false;editPage=null
        }){Text(if(addPage) "Add page to notebook" else "Apply page changes")}},dismissButton={TextButton(onClick={addPage=false;editPage=null}){Text("Cancel")}})
    }
    if(manage) AlertDialog(onDismissRequest={manage=false},title={Text("Notebook pages")},text={LazyColumn(Modifier.heightIn(max=480.dp)) {
        items(state.pages.sortedBy{it.order},key={it.metadata.id.toString()}) { item ->
            Text(item.title ?: "Page ${item.order+1}")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(enabled=!book!!.archived,onClick={manage=false;editPage=item}){Text("Edit ${item.order+1}")}
                TextButton(enabled=!book.archived,onClick={model.notebooks.editPage(item.metadata.id,item.title,item.paper,!item.archived)}){
                    Text(if(item.archived) "Restore page ${item.order+1}" else "Archive page ${item.order+1}")}
                TextButton(enabled=!book.archived && item.order>0,onClick={model.notebooks.movePage(item.metadata.id,-1)}){Text("Move page ${item.order+1} earlier")}
                TextButton(enabled=!book.archived && item.order<state.pages.size-1,onClick={model.notebooks.movePage(item.metadata.id,1)}){Text("Move page ${item.order+1} later")}
            }
            if(item.archived) Text("Archived · Handwriting retained",style=MaterialTheme.typography.labelSmall)
        }
    }},confirmButton={TextButton(onClick={manage=false}){Text("Done")}})
}

@Composable
fun TemplateChoices(selected: PaperTemplate, change: (PaperTemplate)->Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        PaperTemplate.entries.forEach { paper -> FilterChip(selected=selected==paper,onClick={change(paper)},label={Text(paper.name.lowercase().replaceFirstChar{it.titlecase()})}) }
    }
}
