package com.example.customdialer

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import coil.compose.AsyncImage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// --- ACTIVITY PRINCIPAL ---
class MainActivity : ComponentActivity() {
    private val viewModel: DialerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    DialerScreen(viewModel = viewModel)
                }
            }
        }
    }
}

// --- BASE DE DATOS (ROOM) ---
@Entity(tableName = "code_images")
data class CodeImage(
    @PrimaryKey val code: String,
    val imageUri: String
)

@Dao
interface CodeImageDao {
    @Query("SELECT * FROM code_images ORDER BY code")
    fun getAllCodes(): Flow<List<CodeImage>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCode(codeImage: CodeImage)

    @Delete
    suspend fun deleteCode(codeImage: CodeImage)
}

@Database(entities = [CodeImage::class], version = 1, exportSchema = false)
abstract class DialerDatabase : RoomDatabase() {
    abstract fun codeImageDao(): CodeImageDao

    companion object {
        @Volatile
        private var INSTANCE: DialerDatabase? = null

        fun getDatabase(context: Context): DialerDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    DialerDatabase::class.java,
                    "dialer_database"
                ).build().also { INSTANCE = it }
            }
    }
}

// --- VIEWMODEL (LÓGICA) ---
class DialerViewModel(application: Application) : AndroidViewModel(application) {
    private val dao = DialerDatabase.getDatabase(application).codeImageDao()

    // Eagerly: el valor debe estar disponible aunque el diálogo de ajustes esté cerrado,
    // porque onKeyPressed() lo consulta en cada tecla.
    val savedCodes: StateFlow<List<CodeImage>> = dao.getAllCodes()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _dialedNumber = MutableStateFlow("")
    val dialedNumber: StateFlow<String> = _dialedNumber.asStateFlow()

    private val _activeImageUri = MutableStateFlow<String?>(null)
    val activeImageUri: StateFlow<String?> = _activeImageUri.asStateFlow()

    // Mensajes puntuales para la UI (errores, confirmaciones)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private var matchJob: Job? = null

    fun onKeyPressed(char: String) {
        _dialedNumber.value += char
        checkForMatch()
    }

    fun onDeletePressed() {
        matchJob?.cancel()
        _dialedNumber.value = _dialedNumber.value.dropLast(1)
    }

    fun onClearAll() {
        matchJob?.cancel()
        _dialedNumber.value = ""
    }

    /**
     * Si el número coincide con un código:
     *  - y no existe otro código más largo que empiece igual -> muestra la imagen ya.
     *  - si existe uno más largo (ej. *123# y *123#4) -> espera un instante por si el
     *    usuario sigue escribiendo, y solo entonces muestra la imagen.
     */
    private fun checkForMatch() {
        matchJob?.cancel()
        val current = _dialedNumber.value
        val codes = savedCodes.value
        val match = codes.firstOrNull { it.code == current } ?: return

        val hasLongerCode = codes.any {
            it.code.length > current.length && it.code.startsWith(current)
        }

        if (!hasLongerCode) {
            _activeImageUri.value = match.imageUri
        } else {
            matchJob = viewModelScope.launch {
                delay(PREFIX_WAIT_MS)
                if (_dialedNumber.value == current) {
                    _activeImageUri.value = match.imageUri
                }
            }
        }
    }

    fun dismissImage() {
        matchJob?.cancel()
        _activeImageUri.value = null
        // Se limpia el número porque era un código "secreto" ya consumido.
        // Si prefieres conservarlo, elimina la línea siguiente.
        _dialedNumber.value = ""
    }

    fun saveCodeMapping(code: String, uri: Uri): Boolean {
        val cleanCode = code.trim()
        if (cleanCode.isEmpty()) return false

        // Si no se puede conservar el permiso, la imagen dejaría de cargar tras reiniciar:
        // mejor avisar y no guardar.
        try {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            _messages.tryEmit("No se pudo conservar el acceso a la foto. Elige otra.")
            return false
        }

        viewModelScope.launch {
            dao.insertCode(CodeImage(code = cleanCode, imageUri = uri.toString()))
            _messages.emit("Guardado: $cleanCode")
        }
        return true
    }

    fun deleteCodeMapping(codeImage: CodeImage) {
        viewModelScope.launch {
            dao.deleteCode(codeImage)
            try {
                getApplication<Application>().contentResolver.releasePersistableUriPermission(
                    Uri.parse(codeImage.imageUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Si ya no existía el permiso, no pasa nada.
            }
        }
    }

    private companion object {
        const val PREFIX_WAIT_MS = 800L
    }
}

// --- INTERFAZ GRÁFICA (COMPOSE) ---
private val KEY_ROWS = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf("*", "0", "#")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialerScreen(viewModel: DialerViewModel) {
    val context = LocalContext.current
    val dialedNumber by viewModel.dialedNumber.collectAsState()
    val activeImageUri by viewModel.activeImageUri.collectAsState()
    val savedCodes by viewModel.savedCodes.collectAsState()
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Teléfono") },
                actions = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Configurar")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = dialedNumber,
                fontSize = 36.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                KEY_ROWS.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        row.forEach { symbol ->
                            DialKey(symbol = symbol, onClick = { viewModel.onKeyPressed(symbol) })
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(modifier = Modifier.size(56.dp))

                FloatingActionButton(
                    onClick = { startDial(context, dialedNumber) },
                    containerColor = Color(0xFF2E7D32),
                    shape = CircleShape,
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(Icons.Default.Call, contentDescription = "Llamar", tint = Color.White)
                }

                BackspaceButton(
                    visible = dialedNumber.isNotEmpty(),
                    onClick = viewModel::onDeletePressed,
                    onLongClick = viewModel::onClearAll
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    activeImageUri?.let { uriStr ->
        FullScreenImage(uriStr = uriStr, onDismiss = viewModel::dismissImage)
    }

    if (showSettingsDialog) {
        SettingsDialog(
            savedCodes = savedCodes,
            onSave = viewModel::saveCodeMapping,
            onDelete = viewModel::deleteCodeMapping,
            onDismiss = { showSettingsDialog = false }
        )
    }
}

@Composable
private fun DialKey(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, fontSize = 28.sp)
    }
}

/** Toque corto: borra un dígito. Pulsación larga: borra todo. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BackspaceButton(
    visible: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .combinedClickable(
                enabled = visible,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (visible) {
            Icon(Icons.AutoMirrored.Filled.Backspace, contentDescription = "Borrar")
        }
    }
}

private fun startDial(context: Context, number: String) {
    if (number.isBlank()) return
    val intent = Intent(Intent.ACTION_DIAL).apply {
        data = Uri.parse("tel:${Uri.encode(number)}")
    }
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No hay una app de teléfono disponible", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun FullScreenImage(uriStr: String, onDismiss: () -> Unit) {
    var loadFailed by remember(uriStr) { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (loadFailed) {
                Text("No se pudo cargar la imagen", color = Color.White)
            } else {
                AsyncImage(
                    model = Uri.parse(uriStr),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    onError = { loadFailed = true }
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cerrar", tint = Color.White)
            }
        }
    }
}

@Composable
private fun SettingsDialog(
    savedCodes: List<CodeImage>,
    onSave: (String, Uri) -> Boolean,
    onDelete: (CodeImage) -> Unit,
    onDismiss: () -> Unit
) {
    var inputCode by rememberSaveable { mutableStateOf("") }
    var selectedUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) selectedUri = uri }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Asignar Imágenes") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = inputCode,
                    // Solo caracteres que se pueden marcar con el teclado de la app
                    onValueChange = { inputCode = it.filter { c -> c in "0123456789*#" } },
                    label = { Text("Código (ej: *123#)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { picker.launch(arrayOf("image/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (selectedUri != null) "Foto lista ✓" else "Elegir foto")
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        selectedUri?.let { uri ->
                            if (onSave(inputCode, uri)) {
                                inputCode = ""
                                selectedUri = null
                            }
                        }
                    },
                    enabled = inputCode.isNotBlank() && selectedUri != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Guardar combinación")
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (savedCodes.isEmpty()) {
                    Text(
                        "Aún no hay códigos guardados",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                        items(savedCodes, key = { it.code }) { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = Uri.parse(item.imageUri),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                )
                                Text(
                                    item.code,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(onClick = { onDelete(item) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Eliminar ${item.code}",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    )
}
