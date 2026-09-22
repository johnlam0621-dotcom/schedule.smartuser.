<template>
  <main class="inspector-page">
    <div class="inspector-tabs">
      <button type="button" :class="{ active: activeTab === 'route' }" @click="activeTab = 'route'">Route</button>
      <button type="button" :class="{ active: activeTab === 'shift' }" @click="openShift">Shift</button>
    </div>

    <section v-if="activeTab === 'route'" class="inspector-card route-section">
      <div class="inspector-route-head">
        <div>
          <h2>{{ routeData.inspectorName || 'Inspector' }}'s<br />route</h2>
          <p>{{ routeData.date }} /<br />{{ routeData.area || 'Assigned area' }}</p>
        </div>
        <input v-model="selectedDate" type="date" @change="loadRoute" />
      </div>

      <div class="inspector-metrics">
        <div><strong>{{ routeData.stopCount || 0 }}</strong><span>stops</span></div>
        <div><strong>{{ routeData.doneCount || 0 }}</strong><span>done</span></div>
        <div><strong>{{ routeData.nextTime || '--:--' }}</strong><span>next</span></div>
      </div>

      <section v-if="routeData.stops?.length" class="inspector-route-map">
        <div class="inspector-map-heading">
          <div>
            <h3>Today's route map</h3>
            <p>Stops are numbered in inspection-time order. The green marker is the next job.</p>
          </div>
          <a
            v-if="fullRouteUrl"
            class="secondary-action inspector-full-route-link"
            :href="fullRouteUrl"
            target="_blank"
            rel="noreferrer"
          >Open full route</a>
        </div>
        <div ref="routeMapElement" class="inspector-route-map-canvas" aria-label="Inspector route map"></div>
        <div v-if="nextStop" class="inspector-next-stop">
          <strong>Next: {{ nextStop.start }} — {{ nextStop.customerName }}</strong>
          <span>{{ nextStop.address }}</span>
          <a :href="navigationUrl(nextStop.address)" target="_blank" rel="noreferrer">Start navigation</a>
        </div>
        <p v-if="mapNotice" class="inspector-map-notice">{{ mapNotice }}</p>
        <p v-if="mapError" class="inspector-map-notice error">{{ mapError }}</p>
      </section>

      <p v-if="message" class="inspector-message" :class="{ error: hasError }">{{ message }}</p>
      <div v-if="loading" class="inspector-notice">Loading route…</div>
      <div v-else-if="!routeData.stops?.length" class="inspector-notice">No inspections assigned for this date.</div>

      <article
        v-for="stop in routeData.stops || []"
        :key="stop.id"
        class="inspector-stop"
        :class="{ completed: stop.fieldStatus === 'done', unavailable: stop.fieldStatus === 'customer_unavailable' }"
      >
        <div class="stop-title">
          <div>
            <strong class="stop-time">{{ stop.start }} - {{ stop.end }}</strong>
            <strong>{{ stop.customerName }}</strong>
            <span>MACID: {{ stop.macId || 'missing' }}</span>
            <span>{{ stop.projects || 'Inspection' }} / {{ stop.durationMinutes }} min</span>
          </div>
          <span class="status-badge">{{ statusLabel(stop.fieldStatus) }}</span>
        </div>
        <p>{{ stop.address }}</p>
        <p>{{ [stop.suburb, stop.cityCouncil].filter(Boolean).join(' / ') }}</p>

        <div class="two-actions">
          <a class="primary-action" :href="mapUrl(stop.address)" target="_blank" rel="noreferrer">Open map</a>
          <a class="secondary-action" :href="phoneUrl(stop.phoneNumber)">Call customer</a>
        </div>
        <div class="status-actions">
          <button v-if="stop.fieldStatus !== 'done'" type="button" :disabled="stop.fieldStatus === 'customer_unavailable'" @click="markJobDone(stop)">Job Done</button>
          <button v-else type="button" class="secondary" @click="setStatus(stop, 'fixed')">Mark not done</button>
          <button
            v-if="stop.fieldStatus !== 'customer_unavailable'"
            type="button"
            class="secondary"
            @click="openUnavailableReason(stop)"
          >Customer unavailable</button>
          <button v-else type="button" class="secondary" @click="setStatus(stop, 'fixed')">Bring back</button>
        </div>
        <div v-if="unavailableReasonId === stop.id" class="unavailable-reason-form">
          <label>
            Why is the customer unavailable?
            <textarea
              v-model="unavailableReason"
              rows="3"
              maxlength="1000"
              placeholder="Example: No answer at the door or by phone"
            ></textarea>
          </label>
          <div class="unavailable-reason-actions">
            <button type="button" :disabled="!unavailableReason.trim()" @click="saveUnavailable(stop)">Save as unavailable</button>
            <button type="button" class="secondary" @click="cancelUnavailableReason">Cancel</button>
          </div>
        </div>
        <div v-if="quickNoteId === stop.id" class="inspector-quick-note-form">
          <label>
            Inspector note
            <textarea
              v-model="quickNote"
              rows="3"
              maxlength="1000"
              placeholder="Add a note about this inspection"
            ></textarea>
          </label>
          <div class="unavailable-reason-actions">
            <button type="button" :disabled="!quickNote.trim()" @click="saveQuickNote(stop)">Save note</button>
            <button type="button" class="secondary" @click="cancelQuickNote">Cancel</button>
          </div>
        </div>
        <div v-if="unavailableReasonId !== stop.id && quickNoteId !== stop.id" class="inspector-note-display" :class="{ unavailable: stop.fieldStatus === 'customer_unavailable' }">
          <strong>{{ stop.fieldStatus === 'customer_unavailable' ? 'Inspector note — customer unavailable' : 'Inspector note' }}</strong>
          <span>{{ stop.inspectorRemark || 'No note has been entered yet.' }}</span>
          <button
            type="button"
            class="secondary unavailable-note-edit"
            @click="openQuickNote(stop)"
          >{{ stop.inspectorRemark ? 'Edit note' : 'Add note' }}</button>
        </div>
        <button type="button" class="expand-button" @click="toggleDetails(stop)">
          {{ expandedId === stop.id ? 'Collapse details' : 'Expand details' }}
        </button>

        <div v-if="expandedId !== stop.id" class="inspector-notice compact">Tap Expand details to view photos and email.</div>
        <div v-else class="stop-details">
          <label>Customer email<input v-model.trim="stop.customerEmail" type="email" placeholder="customer@email.com" /></label>
          <button type="button" class="secondary" @click="saveDetails(stop)">Save details</button>
          <div class="inspection-submit-panel" :class="{ ready: requiredPhotosReady(stop) }">
            <div>
              <strong>Submit inspection</strong>
              <span v-if="stop.fieldStatus === 'done'">This inspection has been submitted.</span>
              <span v-else-if="requiredPhotosReady(stop)">All required inspection media are uploaded. The inspection is ready to submit.</span>
              <span v-else>Upload all required inspection media before submitting ({{ requiredPhotoCount(stop) }}/{{ requiredPhotoTotal(stop) }} ready).</span>
            </div>
            <button
              type="button"
              :disabled="stop.fieldStatus === 'done' || stop.fieldStatus === 'customer_unavailable' || !requiredPhotosReady(stop)"
              @click="markJobDone(stop)"
            >{{ stop.fieldStatus === 'done' ? 'Submitted' : 'Submit inspection' }}</button>
          </div>
          <div v-if="stop.schedulerRemarks" class="inspector-notice compact">
            <strong>Scheduler remark</strong><br />{{ stop.schedulerRemarks }}
          </div>

          <div class="photo-heading">
            <div><strong>Photo uploads</strong><span>Photos start immediately while one video continues in the background. Keep SmartUser open until every file finishes.</span></div>
            <span class="file-count">{{ photoCount(stop) }} files</span>
          </div>
          <div class="photo-product-selector" role="tablist" aria-label="Inspection product">
            <button
              v-for="product in assignedPhotoProducts(stop)"
              :key="product.key"
              type="button"
              role="tab"
              :aria-selected="selectedPhotoProduct(stop) === product.key"
              :class="{ active: selectedPhotoProduct(stop) === product.key }"
              @click="setPhotoProduct(stop, product.key)"
            >
              <strong>{{ product.label }}</strong>
              <span>{{ product.description }}</span>
            </button>
          </div>
          <div
            v-if="requiredPhotoTotal(stop) > 0 && selectedProductHasRequirements(stop)"
            class="required-photo-progress"
            :class="{ complete: requiredPhotosReady(stop) }"
          >
            <strong>Required inspection media: {{ requiredPhotoCount(stop) }}/{{ requiredPhotoTotal(stop) }}</strong>
            <span v-if="!requiredPhotosReady(stop)">{{ requiredPhotoInstruction(stop) }}</span>
            <span v-else>All required media are ready.</span>
          </div>
          <section
            v-for="section in productPhotoSections(stop)"
            :key="section.key"
            class="product-photo-section"
          >
            <div class="product-photo-subheading">
              <strong>{{ section.title }}</strong>
              <span>{{ section.description }}</span>
              <span class="file-count">{{ sectionFileCount(section) }} files</span>
            </div>
            <div class="photo-grid">
              <div
              v-for="slot in section.slots"
              :key="`${section.key}-${slot.key}`"
              class="photo-slot"
              :class="{
                required: isRequiredPhotoSlot(stop, slot),
                missing: isRequiredPhotoSlot(stop, slot) && !requiredSlotSatisfied(stop, slot)
              }"
            >
              <div class="photo-preview" :class="{ 'video-file-preview': isVideoSlot(slot) }">
                <span v-if="isVideoSlot(slot) && slot.files?.length">{{ slot.files[0].name }}</span>
                <img v-else-if="previewUrl(slot)" :src="previewUrl(slot)" :alt="slot.label" />
                <span v-else>No Data</span>
              </div>
              <strong>{{ slot.label }}</strong>
              <span v-if="isRequiredPhotoSlot(stop, slot)" class="required-photo-badge">{{ requiredSlotBadge(stop, slot) }}</span>
              <div v-if="!isVideoSlot(slot)" class="photo-source-actions">
                <button type="button" class="camera-photo-action" :disabled="isUploading(stop, slot)" @click="takePhoto(stop, slot)">
                  {{ uploadButtonText(stop, slot, 'Take photo') }}
                </button>
                <button type="button" class="secondary" :disabled="isUploading(stop, slot)" @click="choosePhoto(stop, slot)">
                  Choose file
                </button>
              </div>
              <div v-else class="photo-source-actions">
                <button type="button" class="camera-photo-action" :disabled="isUploading(stop, slot)" @click="recordVideo(stop, slot)">
                  {{ uploadButtonText(stop, slot, 'Record video') }}
                </button>
                <button type="button" class="secondary" :disabled="isUploading(stop, slot)" @click="choosePhoto(stop, slot)">
                  Choose video
                </button>
              </div>
              <div v-if="slot.files?.length" class="uploaded-file-list">
                <div v-for="file in slot.files" :key="file.id" class="uploaded-file-item">
                  <div class="uploaded-file-row">
                    <span :title="file.name">{{ file.name }}</span>
                    <button v-if="isVideoSlot(slot)" type="button" class="annotate-upload" @click="openVideo(file, slot.label)">Play</button>
                    <a v-if="isVideoSlot(slot)" class="video-file-download" :href="downloadVideoUrl(file)" download>Download</a>
                    <button v-if="!isVideoSlot(slot) && isImageFile(file)" type="button" class="annotate-upload" @click="openAnnotationEditor(stop, slot, file)">
                      Draw
                    </button>
                    <button v-if="!isVideoSlot(slot) && isImageFile(file)" type="button" class="photo-remark-action" @click="openPhotoRemark(file)">
                      {{ photoRemarkEditor.photoId === file.id ? 'Close' : 'Remark' }}
                    </button>
                    <button type="button" class="delete-upload" :disabled="isDeleting(file)" @click="deleteUploadedFile(stop, slot, file)">
                      {{ isDeleting(file) ? 'Deleting…' : 'Delete' }}
                    </button>
                  </div>
                  <div v-if="file.remark && photoRemarkEditor.photoId !== file.id" class="saved-photo-remark">
                    <strong>Remark:</strong> {{ file.remark }}
                  </div>
                  <div v-if="photoRemarkEditor.photoId === file.id" class="photo-remark-editor">
                    <textarea v-model="photoRemarkEditor.value" maxlength="2000" rows="3" placeholder="Add a remark for this photo"></textarea>
                    <div>
                      <button type="button" class="secondary" :disabled="photoRemarkEditor.saving" @click="cancelPhotoRemark">Cancel</button>
                      <button type="button" :disabled="photoRemarkEditor.saving" @click="savePhotoRemark(stop, file)">
                        {{ photoRemarkEditor.saving ? 'Saving…' : 'Save remark' }}
                      </button>
                    </div>
                  </div>
                </div>
              </div>
              <small>{{ slot.files?.length || 0 }} file(s)<template v-if="isVideoSlot(slot)"> · 3 GB maximum</template></small>
              </div>
            </div>
          </section>

          <div class="photo-heading additional-photo-heading">
            <div>
              <strong>Additional / Product Photos</strong>
              <span>Optional supporting evidence for any product.</span>
            </div>
            <span class="file-count">{{ additionalPhotoCount(stop) }} files</span>
          </div>
          <div class="photo-grid">
            <div v-for="slot in additionalPhotoSlots(stop)" :key="slot.key" class="photo-slot">
              <div class="photo-preview">
                <img v-if="previewUrl(slot)" :src="previewUrl(slot)" :alt="slot.label" />
                <span v-else>No Data</span>
              </div>
              <strong>{{ slot.label }}</strong>
              <div class="photo-source-actions">
                <button type="button" class="camera-photo-action" :disabled="isUploading(stop, slot)" @click="takePhoto(stop, slot)">
                  {{ uploadButtonText(stop, slot, 'Take photo') }}
                </button>
                <button type="button" class="secondary" :disabled="isUploading(stop, slot)" @click="choosePhoto(stop, slot)">
                  Choose file
                </button>
              </div>
              <div v-if="slot.files?.length" class="uploaded-file-list">
                <div v-for="file in slot.files" :key="file.id" class="uploaded-file-item">
                  <div class="uploaded-file-row">
                    <span :title="file.name">{{ file.name }}</span>
                    <button v-if="isImageFile(file)" type="button" class="annotate-upload" @click="openAnnotationEditor(stop, slot, file)">
                      Draw
                    </button>
                    <button v-if="isImageFile(file)" type="button" class="photo-remark-action" @click="openPhotoRemark(file)">
                      {{ photoRemarkEditor.photoId === file.id ? 'Close' : 'Remark' }}
                    </button>
                    <button type="button" class="delete-upload" :disabled="isDeleting(file)" @click="deleteUploadedFile(stop, slot, file)">
                      {{ isDeleting(file) ? 'Deleting…' : 'Delete' }}
                    </button>
                  </div>
                  <div v-if="file.remark && photoRemarkEditor.photoId !== file.id" class="saved-photo-remark">
                    <strong>Remark:</strong> {{ file.remark }}
                  </div>
                  <div v-if="photoRemarkEditor.photoId === file.id" class="photo-remark-editor">
                    <textarea v-model="photoRemarkEditor.value" maxlength="2000" rows="3" placeholder="Add a remark for this photo"></textarea>
                    <div>
                      <button type="button" class="secondary" :disabled="photoRemarkEditor.saving" @click="cancelPhotoRemark">Cancel</button>
                      <button type="button" :disabled="photoRemarkEditor.saving" @click="savePhotoRemark(stop, file)">
                        {{ photoRemarkEditor.saving ? 'Saving…' : 'Save remark' }}
                      </button>
                    </div>
                  </div>
                </div>
              </div>
              <small>{{ slot.files?.length || 0 }} file(s)</small>
            </div>
          </div>

          <div class="photo-heading additional-photo-heading">
            <div>
              <strong>Inspection Videos</strong>
              <span>Upload up to {{ videoLimit(stop) }} videos. For the fastest upload, use Record video; selected files stream directly. Maximum size: 3 GB each.</span>
            </div>
            <span class="file-count">{{ videoCount(stop) }}/{{ videoLimit(stop) }} videos</span>
          </div>
          <div class="photo-grid video-upload-grid">
            <div v-for="slot in videoSlots(stop)" :key="slot.key" class="photo-slot video-slot">
              <div class="photo-preview video-file-preview">
                <span v-if="slot.files?.length">{{ slot.files[0].name }}</span>
                <span v-else>No video uploaded</span>
              </div>
              <strong>{{ slot.label }}</strong>
              <div class="photo-source-actions">
                <button type="button" class="camera-photo-action" :disabled="isUploading(stop, slot)" @click="recordVideo(stop, slot)">
                  {{ uploadButtonText(stop, slot, 'Record video') }}
                </button>
                <button type="button" class="secondary" :disabled="isUploading(stop, slot)" @click="choosePhoto(stop, slot)">
                  Choose video
                </button>
              </div>
              <div v-if="slot.files?.length" class="uploaded-file-list">
                <div v-for="file in slot.files" :key="file.id" class="uploaded-file-row">
                  <span :title="file.name">{{ file.name }}</span>
                  <button type="button" class="annotate-upload" @click="openVideo(file, slot.label)">Play</button>
                  <a class="video-file-download" :href="downloadVideoUrl(file)" download>Download</a>
                  <button type="button" class="delete-upload" :disabled="isDeleting(file)" @click="deleteUploadedFile(stop, slot, file)">
                    {{ isDeleting(file) ? 'Deleting…' : 'Delete' }}
                  </button>
                </div>
              </div>
              <small>{{ slot.files?.length || 0 }} file(s) · 3 GB maximum</small>
            </div>
          </div>
        </div>
      </article>
    </section>

    <section v-else class="inspector-card shift-section">
      <h2>Next week availability</h2>
      <p>Submit by Wednesday/Thursday. Once submitted, only a manager can adjust it.</p>
      <p v-if="message" class="inspector-message" :class="{ error: hasError }">{{ message }}</p>
      <div class="inspector-notice">
        <strong>Week of {{ shiftData.weekStart }}</strong><br />
        Saturday and Sunday are available if you tick working.
      </div>
      <article v-for="day in shiftData.days || []" :key="day.date" class="shift-day">
        <div class="shift-title"><strong>{{ day.date }}</strong><span>{{ day.locked ? 'Submitted' : 'Draft' }}</span></div>
        <label class="working-check"><input v-model="day.working" type="checkbox" :disabled="day.locked" /> Working</label>
        <div class="shift-times">
          <label>Start<input v-model="day.start" type="time" min="09:00" max="16:30" step="1800" :disabled="day.locked" /></label>
          <label>Last inspection start<input v-model="day.end" type="time" min="09:00" max="16:30" step="1800" :disabled="day.locked" /></label>
        </div>
        <button type="button" :disabled="day.locked" @click="submitShift(day)">Submit shift</button>
      </article>
    </section>

    <input ref="photoInput" class="hidden-file" type="file" :accept="uploadAccept" @change="uploadPhoto" />
    <!-- 手机拍照入口使用后置摄像头；仍复用原有压缩、校验和上传流程。 -->
    <input ref="cameraInput" class="hidden-file" type="file" accept="image/*" capture="environment" @change="uploadPhoto" />
    <!-- 直接录制可让手机相机输出已经硬件压缩的视频，避免浏览器内转码造成更长等待。 -->
    <input ref="videoCameraInput" class="hidden-file" type="file" accept="video/*" capture="environment" @change="uploadPhoto" />

    <Teleport to="body">
      <div v-if="activeVideo.open" class="inspector-video-backdrop" @click.self="closeVideo">
        <section class="inspector-video-dialog" role="dialog" aria-modal="true" aria-labelledby="inspector-video-title">
          <button type="button" class="annotation-close" aria-label="Close video" @click="closeVideo">×</button>
          <div v-if="activeVideo.preparing" class="inspector-video-preparing">{{ activeVideo.status }}</div>
          <video v-else-if="activeVideo.url" :src="activeVideo.url" controls autoplay preload="metadata" @error="videoPlaybackFailed"></video>
          <footer>
            <strong id="inspector-video-title">{{ activeVideo.label }}</strong>
            <span>{{ activeVideo.file?.name }}</span>
            <a v-if="activeVideo.url" :href="`${activeVideo.url}?download=true`" download>Download smaller video</a>
            <a v-if="activeVideo.file" :href="downloadVideoUrl(activeVideo.file)" download>Download original video</a>
          </footer>
        </section>
      </div>
    </Teleport>

    <Teleport to="body">
      <div v-if="annotationEditor.open" class="annotation-backdrop" @click.self="closeAnnotationEditor">
        <section class="annotation-dialog" role="dialog" aria-modal="true" aria-labelledby="annotation-title">
          <header class="annotation-header">
            <div>
              <strong id="annotation-title">Mark up inspection photo</strong>
              <span>{{ annotationEditor.file?.name }}</span>
            </div>
            <button type="button" class="annotation-close" aria-label="Close photo editor" @click="closeAnnotationEditor">×</button>
          </header>

          <div class="annotation-toolbar">
            <button type="button" :class="{ active: annotationEditor.tool === 'draw' }" @click="annotationEditor.tool = 'draw'">Draw</button>
            <label class="annotation-color">Colour <input v-model="annotationEditor.color" type="color" aria-label="Drawing colour" /></label>
            <label class="annotation-size">Brush <input v-model.number="annotationEditor.brushSize" type="range" min="2" max="28" step="1" /></label>
            <button type="button" :disabled="!annotationActions.length" @click="undoAnnotation">Undo</button>
            <button type="button" :disabled="!annotationActions.length" @click="clearAnnotations">Clear marks</button>
          </div>

          <div class="annotation-text-tools">
            <input v-model.trim="annotationEditor.text" type="text" maxlength="100" placeholder="Optional typed note" @keyup.enter="startTextPlacement" />
            <button type="button" :disabled="!annotationEditor.text" :class="{ active: annotationEditor.tool === 'text' }" @click="startTextPlacement">
              Place text
            </button>
            <span v-if="annotationEditor.tool === 'text'">Tap the photo where the note should appear.</span>
          </div>

          <div class="annotation-stage">
            <p v-if="annotationEditor.loading">Loading photo editor…</p>
            <p v-else-if="annotationEditor.error" class="annotation-error">{{ annotationEditor.error }}</p>
            <canvas
              v-show="!annotationEditor.loading && !annotationEditor.error"
              ref="annotationCanvas"
              aria-label="Photo drawing area"
              @pointerdown="startAnnotationPointer"
              @pointermove="moveAnnotationPointer"
              @pointerup="endAnnotationPointer"
              @pointercancel="endAnnotationPointer"
            ></canvas>
          </div>

          <footer class="annotation-footer">
            <span v-if="annotationEditor.mode === 'preupload'">Review or mark the photo now. Nothing is uploaded until you confirm.</span>
            <span v-else>The original photo will remain unchanged.</span>
            <div>
              <button type="button" class="secondary" :disabled="annotationEditor.saving" @click="closeAnnotationEditor">Cancel</button>
              <button
                type="button"
                :disabled="annotationEditor.loading || annotationEditor.saving || (annotationEditor.mode !== 'preupload' && !annotationActions.length)"
                @click="saveAnnotatedCopy"
              >
                {{ annotationEditor.saving ? 'Uploading…' : annotationEditor.mode === 'preupload' ? 'Confirm and upload' : 'Save annotated copy' }}
              </button>
            </div>
          </footer>
        </section>
      </div>
    </Teleport>
  </main>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import http from '../api/http'
import { queueBackgroundUpload } from '../store/uploadQueue'
import { authState } from '../store/auth'
import { originalVideoDownloadUrl, prepareVideoPlayback } from '../utils/videoPlayback'
import { assignedProductKeys, resolveAssignedProduct } from '../utils/assignedProducts'

const activeTab = ref('route')
const selectedDate = ref(localDateText(new Date()))
const loading = ref(false)
const message = ref('')
const hasError = ref(false)
const expandedId = ref(null)
const unavailableReasonId = ref(null)
const unavailableReason = ref('')
const quickNoteId = ref(null)
const quickNote = ref('')
const routeData = reactive({ stops: [] })
const shiftData = reactive({ days: [] })
const photoInput = ref(null)
const cameraInput = ref(null)
const videoCameraInput = ref(null)
const uploadTarget = ref(null)
const uploadAccept = ref('image/*')
const previews = reactive({})
const uploadProgress = reactive({})
const deleteProgress = reactive({})
const photoProductSelection = reactive({})
const photoRemarkEditor = reactive({ photoId: null, value: '', saving: false })
const activeVideo = reactive({ open: false, preparing: false, status: '', url: '', file: null, label: '' })
const annotationCanvas = ref(null)
const annotationActions = ref([])
const annotationEditor = reactive({
  open: false,
  loading: false,
  saving: false,
  error: '',
  tool: 'draw',
  color: '#ff2d2d',
  brushSize: 8,
  text: '',
  mode: 'existing',
  pendingFile: null,
  stop: null,
  slot: null,
  file: null
})
const googleMapsKey = import.meta.env.VITE_GOOGLE_MAPS_KEY || ''
const routeMapElement = ref(null)
const mapNotice = ref('')
const mapError = ref('')
const requiredPhotoKeys = new Set([
  'switchboard',
  'switchboard_main_switch',
  'ducted_gas_vents',
  'premises_roof',
  'indoor_location_1',
  'indoor_location_2',
  'indoor_location_3',
  'indoor_location_4',
  'condenser_outdoor_location'
])
const batterySalesUploadKeys = new Set([
  'switchboard',
  'switchboard_main_switch',
  'premises_roof',
  'ducted_gas_vents',
  'condenser_outdoor_location',
  'drawn_floor_plan_measurements',
  'battery_measurements',
  'signed_documents'
])
const macPlanUploadKeys = new Set([
  'mac_floor_plan',
  'mac_measurements'
])
const photoProducts = [
  {
    key: 'mac',
    label: 'MAC',
    description: 'MAC inspection photos',
    group: 'standard'
  },
  {
    key: 'dac',
    label: 'DAC',
    description: 'DAC photos, 12 additional photos and 9 videos',
    group: 'standard'
  },
  {
    key: 'battery_solar',
    label: 'Battery & Solar Panels',
    description: 'Battery, panels and approvals',
    group: 'battery_solar'
  },
  {
    key: 'heat_pump',
    label: 'Heat Pump',
    description: 'Heat-pump installation photos',
    group: 'heat_pump'
  }
]
let googleRouteMap = null
let googleRouteMarkers = []
let googleDirectionsRenderer = null
let googleRouteLine = null
let googleScriptPromise = null
let routeMapGeneration = 0
let annotationBaseImage = null
let annotationImageUrl = ''
let activeAnnotationStroke = null

const nextStop = computed(() => {
  const stops = routeData.stops || []
  return stops.find((stop) => stop.start === routeData.nextTime && stop.fieldStatus !== 'done')
    || stops.find((stop) => stop.fieldStatus !== 'done' && stop.fieldStatus !== 'customer_unavailable')
    || null
})

const fullRouteUrl = computed(() => {
  const addresses = (routeData.stops || []).map((stop) => stop.address).filter(Boolean)
  if (!addresses.length) return ''
  if (addresses.length === 1) return navigationUrl(addresses[0])
  const params = new URLSearchParams({
    api: '1',
    origin: addresses[0],
    destination: addresses[addresses.length - 1],
    travelmode: 'driving'
  })
  if (addresses.length > 2) params.set('waypoints', addresses.slice(1, -1).join('|'))
  return `https://www.google.com/maps/dir/?${params.toString()}`
})

onMounted(() => {
  loadRoute()
  window.addEventListener('keydown', handleAnnotationKeydown)
})
onBeforeUnmount(() => {
  closeVideo()
  Object.values(previews).forEach((url) => URL.revokeObjectURL(url))
  releaseAnnotationImage()
  window.removeEventListener('keydown', handleAnnotationKeydown)
  clearInspectorRouteMap()
})

async function loadRoute() {
  loading.value = true
  message.value = ''
  unavailableReasonId.value = null
  unavailableReason.value = ''
  quickNoteId.value = null
  quickNote.value = ''
  cancelPhotoRemark()
  try {
    const loadedRoute = await http.get('/inspector/route', { params: { date: selectedDate.value } })
    ensureMacPlanUploadSlots(loadedRoute.stops || [])
    Object.assign(routeData, loadedRoute)
    await loadPreviews(routeData.stops || [])
    await nextTick()
    await loadInspectorRouteMap()
  } catch (error) {
    showMessage(error.message, true)
  } finally {
    loading.value = false
  }
}

async function openShift() {
  activeTab.value = 'shift'
  try {
    Object.assign(shiftData, await http.get('/inspector/shifts'))
  } catch (error) {
    showMessage(error.message, true)
  }
}

async function setStatus(stop, status) {
  try {
    await http.put(`/inspector/route/${stop.id}/status`, { status })
    await loadRoute()
  } catch (error) {
    showMessage(error.message, true)
  }
}

async function markJobDone(stop) {
  const missing = missingRequiredPhotoSlots(stop)
  if (missing.length) {
    expandedId.value = stop.id
    showMessage(`Upload all required inspection media before Job Done. Missing: ${missing.map((item) => item.label).join(', ')}.`, true)
    return
  }
  await setStatus(stop, 'done')
}

function openUnavailableReason(stop) {
  cancelQuickNote()
  unavailableReasonId.value = stop.id
  unavailableReason.value = stop.inspectorRemark || ''
}

function cancelUnavailableReason() {
  unavailableReasonId.value = null
  unavailableReason.value = ''
}

function openQuickNote(stop) {
  cancelUnavailableReason()
  quickNoteId.value = stop.id
  quickNote.value = stop.inspectorRemark || ''
}

function cancelQuickNote() {
  quickNoteId.value = null
  quickNote.value = ''
}

async function saveQuickNote(stop) {
  const note = quickNote.value.trim()
  if (!note) {
    showMessage('Enter an inspector note before saving.', true)
    return
  }
  try {
    await http.put(`/inspector/route/${stop.id}/details`, { email: stop.customerEmail, remark: note })
    stop.inspectorRemark = note
    cancelQuickNote()
    showMessage('Inspector note saved in the application.', false)
  } catch (error) {
    showMessage(error.message, true)
  }
}

async function saveUnavailable(stop) {
  const reason = unavailableReason.value.trim()
  if (!reason) {
    showMessage('Enter the reason the customer is unavailable.', true)
    return
  }
  try {
    await http.put(`/inspector/route/${stop.id}/status`, { status: 'customer_unavailable', reason })
    showMessage('Customer marked unavailable and the reason was saved.', false)
    await loadRoute()
  } catch (error) {
    showMessage(error.message, true)
  }
}

async function saveDetails(stop) {
  try {
    await http.put(`/inspector/route/${stop.id}/details`, { email: stop.customerEmail, remark: stop.inspectorRemark })
    showMessage('Customer email saved in the application.', false)
  } catch (error) {
    showMessage(error.message, true)
  }
}

async function submitShift(day) {
  try {
    await http.post('/inspector/shifts', day)
    await openShift()
  } catch (error) {
    showMessage(error.message, true)
  }
}

function toggleDetails(stop) {
  expandedId.value = expandedId.value === stop.id ? null : stop.id
}

function choosePhoto(stop, slot) {
  if (!checkAssignedUploadSlot(stop, slot)) return
  uploadTarget.value = { stop, slot }
  uploadAccept.value = isVideoSlot(slot) ? 'video/mp4,video/quicktime,video/webm,video/x-m4v,video/3gpp' : 'image/*'
  photoInput.value.value = ''
  photoInput.value.click()
}

function takePhoto(stop, slot) {
  if (!checkAssignedUploadSlot(stop, slot)) return
  uploadTarget.value = { stop, slot }
  cameraInput.value.value = ''
  cameraInput.value.click()
}

function recordVideo(stop, slot) {
  if (!checkAssignedUploadSlot(stop, slot)) return
  uploadTarget.value = { stop, slot }
  videoCameraInput.value.value = ''
  videoCameraInput.value.click()
}

async function uploadPhoto(event) {
  const originalFile = event.target.files?.[0]
  const target = uploadTarget.value
  if (!originalFile || !target) return
  const isVideo = isVideoSlot(target.slot)
  const maximumBytes = isVideo ? 3 * 1024 * 1024 * 1024 : 20 * 1024 * 1024
  if (originalFile.size > maximumBytes) {
    showMessage(isVideo ? 'Each video must be 3 GB or smaller.' : 'Each photo must be 20 MB or smaller.', true)
    return
  }
  if (isVideo && !originalFile.type.startsWith('video/')) {
    showMessage('Choose an MP4, MOV, WEBM, M4V or 3GP video file.', true)
    return
  }
  const file = isVideo ? originalFile : await compressInspectionPhoto(originalFile)
  event.target.value = ''
  if (!isVideo) {
    await openPreUploadAnnotationEditor(target.stop, target.slot, file)
    return
  }
  const queuedStop = target.stop
  const queuedSlot = target.slot
  queueBackgroundUpload({
    name: originalFile.name,
    size: originalFile.size,
    description: `${queuedStop.customerName || 'Inspection'} · ${queuedSlot.label}`,
    mediaType: 'video',
    upload: (onProgress) => uploadPreparedFile(queuedStop, queuedSlot, file, originalFile, true, onProgress)
  })
  showMessage('Video added to the background upload queue. You can use other SmartUser pages while it uploads.', false)
}

async function uploadPreparedFile(stop, slot, file, originalFile = file, isVideo = false, onBackgroundProgress = null) {
  const progressKey = uploadKey(stop, slot)
  uploadProgress[progressKey] = 1
  const data = new FormData()
  data.append('categoryKey', slot.key)
  data.append('file', file)
  try {
    const uploaded = file.size >= 10 * 1024 * 1024
      ? await uploadFileInChunks(stop, slot, file, (progress) => {
        uploadProgress[progressKey] = progress
        onBackgroundProgress?.(progress)
      }, isVideo)
      : await http.post(`/inspector/route/${stop.id}/photos`, data, {
        timeout: 0,
        onUploadProgress: (progressEvent) => {
          if (progressEvent.total) {
            const progress = Math.max(1, Math.round(progressEvent.loaded * 100 / progressEvent.total))
            uploadProgress[progressKey] = progress
            onBackgroundProgress?.(progress)
          }
        }
      })
    const uploadedFile = { id: uploaded.id, url: uploaded.url, name: file.name || originalFile.name, remark: '' }
    slot.files = [...(slot.files || []), uploadedFile]
    if (!isVideo) await loadPreview(uploadedFile)
    showMessage(isVideo
      ? 'Video uploaded. You can continue using the page while other files upload.'
      : file.size < originalFile.size
        ? `Photo uploaded after compression (${formatBytes(originalFile.size)} → ${formatBytes(file.size)}).`
        : 'Photo uploaded without reloading the route.', false)
    expandedId.value = stop.id
    return uploadedFile
  } catch (error) {
    showMessage(error.message, true)
    throw error
  } finally {
    delete uploadProgress[progressKey]
  }
}

async function uploadFileInChunks(stop, slot, file, onProgress, isVideo = false) {
  // Videos use parallel chunks for speed. Photos still use their own upload
  // lane, so starting a photo never waits for these video requests to finish.
  const chunkSize = 16 * 1024 * 1024
  const totalChunks = Math.ceil(file.size / chunkSize)
  const uploadId = crypto.randomUUID?.() || `${Date.now().toString(16).padStart(8, '0')}-0000-4000-8000-${Math.random().toString(16).slice(2).padEnd(12, '0').slice(0, 12)}`
  const loadedByChunk = Array(totalChunks).fill(0)
  let nextIndex = 0
  const reportProgress = () => {
    const loaded = loadedByChunk.reduce((sum, bytes) => sum + bytes, 0)
    onProgress(Math.max(1, Math.min(99, Math.round(loaded * 100 / file.size))))
  }
  const worker = async () => {
    while (nextIndex < totalChunks) {
      const index = nextIndex++
      const start = index * chunkSize
      const chunk = file.slice(start, Math.min(file.size, start + chunkSize))
      let lastError = null
      for (let attempt = 1; attempt <= 3; attempt++) {
        try {
          const data = new FormData()
          data.append('categoryKey', slot.key)
          data.append('uploadId', uploadId)
          data.append('chunkIndex', String(index))
          data.append('totalChunks', String(totalChunks))
          data.append('file', chunk, `${file.name}.part${index}`)
          await http.post(`/inspector/route/${stop.id}/photos/chunk`, data, {
            timeout: 0,
            onUploadProgress: (event) => {
              loadedByChunk[index] = Math.min(chunk.size, event.loaded || 0)
              reportProgress()
            }
          })
          loadedByChunk[index] = chunk.size
          reportProgress()
          lastError = null
          break
        } catch (error) {
          lastError = error
          loadedByChunk[index] = 0
          reportProgress()
          if (attempt < 3) await new Promise((resolve) => window.setTimeout(resolve, attempt * 1000))
        }
      }
      if (lastError) throw lastError
    }
  }
  const concurrentChunks = Math.min(3, totalChunks)
  await Promise.all(Array.from({ length: concurrentChunks }, worker))
  return http.post(`/inspector/route/${stop.id}/photos/complete`, {
    categoryKey: slot.key,
    uploadId,
    totalChunks,
    totalSize: file.size,
    originalName: file.name,
    contentType: file.type
  }, { timeout: 0 })
}

async function deleteUploadedFile(stop, slot, file) {
  if (!window.confirm(`Delete ${file.name || 'this uploaded file'}? This cannot be undone.`)) return
  deleteProgress[file.id] = true
  try {
    await http.delete(`/inspector/photos/${file.id}`)
    if (previews[file.id]) {
      URL.revokeObjectURL(previews[file.id])
      delete previews[file.id]
    }
    slot.files = (slot.files || []).filter((item) => item.id !== file.id)
    if (photoRemarkEditor.photoId === file.id) cancelPhotoRemark()
    if (slot.group !== 'video' && slot.files.length) await loadPreview(slot.files[0])
    showMessage('Uploaded file deleted.', false)
    expandedId.value = stop.id
  } catch (error) {
    showMessage(error.message || 'The uploaded file could not be deleted.', true)
  } finally {
    delete deleteProgress[file.id]
  }
}

// 单张照片备注直接保存到数据库，不需要上传额外的备注文件。
function openPhotoRemark(file) {
  if (photoRemarkEditor.photoId === file.id) {
    cancelPhotoRemark()
    return
  }
  photoRemarkEditor.photoId = file.id
  photoRemarkEditor.value = file.remark || ''
  photoRemarkEditor.saving = false
}

function cancelPhotoRemark() {
  photoRemarkEditor.photoId = null
  photoRemarkEditor.value = ''
  photoRemarkEditor.saving = false
}

async function savePhotoRemark(stop, file) {
  const remark = photoRemarkEditor.value.trim()
  photoRemarkEditor.saving = true
  try {
    await http.put(`/inspector/photos/${file.id}/remark`, { remark })
    file.remark = remark
    cancelPhotoRemark()
    expandedId.value = stop.id
    showMessage(remark ? 'Photo remark saved.' : 'Photo remark removed.', false)
  } catch (error) {
    showMessage(error.message || 'The photo remark could not be saved.', true)
  } finally {
    photoRemarkEditor.saving = false
  }
}

function isImageFile(file) {
  return /\.(?:avif|bmp|gif|heic|heif|jpe?g|png|webp)(?:$|[?#])/i.test(file?.name || file?.url || '')
}

async function openAnnotationEditor(stop, slot, file) {
  annotationEditor.open = true
  annotationEditor.loading = true
  annotationEditor.saving = false
  annotationEditor.error = ''
  annotationEditor.tool = 'draw'
  annotationEditor.text = ''
  annotationEditor.mode = 'existing'
  annotationEditor.pendingFile = null
  annotationEditor.stop = stop
  annotationEditor.slot = slot
  annotationEditor.file = file
  annotationActions.value = []
  document.body.classList.add('annotation-open')
  releaseAnnotationImage()
  try {
    const response = await fetch(file.url, { headers: { Authorization: `Bearer ${authState.token}` } })
    if (!response.ok) throw new Error(`Photo download failed (${response.status}).`)
    annotationImageUrl = URL.createObjectURL(await response.blob())
    const image = new Image()
    image.src = annotationImageUrl
    await new Promise((resolve, reject) => {
      image.onload = resolve
      image.onerror = () => reject(new Error('This photo could not be opened for editing.'))
    })
    annotationBaseImage = image
    await nextTick()
    const canvas = annotationCanvas.value
    const maximumDimension = 2400
    const scale = Math.min(1, maximumDimension / Math.max(image.naturalWidth, image.naturalHeight))
    canvas.width = Math.max(1, Math.round(image.naturalWidth * scale))
    canvas.height = Math.max(1, Math.round(image.naturalHeight * scale))
    annotationEditor.loading = false
    await nextTick()
    renderAnnotationCanvas()
  } catch (error) {
    annotationEditor.loading = false
    annotationEditor.error = error.message || 'This photo could not be opened for editing.'
  }
}

async function openPreUploadAnnotationEditor(stop, slot, file) {
  annotationEditor.open = true
  annotationEditor.loading = true
  annotationEditor.saving = false
  annotationEditor.error = ''
  annotationEditor.tool = 'draw'
  annotationEditor.text = ''
  annotationEditor.mode = 'preupload'
  annotationEditor.pendingFile = file
  annotationEditor.stop = stop
  annotationEditor.slot = slot
  annotationEditor.file = { name: file.name }
  annotationActions.value = []
  document.body.classList.add('annotation-open')
  releaseAnnotationImage()
  try {
    annotationImageUrl = URL.createObjectURL(file)
    const image = new Image()
    image.src = annotationImageUrl
    await new Promise((resolve, reject) => {
      image.onload = resolve
      image.onerror = () => reject(new Error('This photo format cannot be opened for marking. Choose a JPG or PNG photo.'))
    })
    annotationBaseImage = image
    await prepareAnnotationCanvas(image)
  } catch (error) {
    annotationEditor.loading = false
    annotationEditor.error = error.message || 'This photo could not be opened for marking.'
  }
}

async function prepareAnnotationCanvas(image) {
  await nextTick()
  const canvas = annotationCanvas.value
  const maximumDimension = 2400
  const scale = Math.min(1, maximumDimension / Math.max(image.naturalWidth, image.naturalHeight))
  canvas.width = Math.max(1, Math.round(image.naturalWidth * scale))
  canvas.height = Math.max(1, Math.round(image.naturalHeight * scale))
  annotationEditor.loading = false
  await nextTick()
  renderAnnotationCanvas()
}

function closeAnnotationEditor() {
  if (annotationEditor.saving) return
  annotationEditor.open = false
  annotationEditor.loading = false
  annotationEditor.error = ''
  annotationEditor.stop = null
  annotationEditor.slot = null
  annotationEditor.file = null
  annotationEditor.pendingFile = null
  annotationEditor.mode = 'existing'
  annotationActions.value = []
  activeAnnotationStroke = null
  document.body.classList.remove('annotation-open')
  releaseAnnotationImage()
}

function releaseAnnotationImage() {
  annotationBaseImage = null
  if (annotationImageUrl) URL.revokeObjectURL(annotationImageUrl)
  annotationImageUrl = ''
}

function handleAnnotationKeydown(event) {
  if (!annotationEditor.open) return
  if (event.key === 'Escape') closeAnnotationEditor()
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'z') {
    event.preventDefault()
    undoAnnotation()
  }
}

function annotationPoint(event) {
  const canvas = annotationCanvas.value
  const rect = canvas.getBoundingClientRect()
  return {
    x: (event.clientX - rect.left) * canvas.width / rect.width,
    y: (event.clientY - rect.top) * canvas.height / rect.height,
    scale: canvas.width / rect.width
  }
}

function startAnnotationPointer(event) {
  if (annotationEditor.loading || annotationEditor.error) return
  event.preventDefault()
  const point = annotationPoint(event)
  if (annotationEditor.tool === 'text') {
    const text = annotationEditor.text.trim()
    if (!text) return
    annotationActions.value.push({
      type: 'text',
      text,
      color: annotationEditor.color,
      fontSize: Math.max(22, annotationEditor.brushSize * 3.5) * point.scale,
      x: point.x,
      y: point.y
    })
    annotationEditor.text = ''
    annotationEditor.tool = 'draw'
    renderAnnotationCanvas()
    return
  }
  annotationCanvas.value.setPointerCapture?.(event.pointerId)
  activeAnnotationStroke = {
    type: 'stroke',
    color: annotationEditor.color,
    width: annotationEditor.brushSize * point.scale,
    points: [{ x: point.x, y: point.y }]
  }
  annotationActions.value.push(activeAnnotationStroke)
  renderAnnotationCanvas()
}

function moveAnnotationPointer(event) {
  if (!activeAnnotationStroke) return
  event.preventDefault()
  const point = annotationPoint(event)
  const previous = activeAnnotationStroke.points[activeAnnotationStroke.points.length - 1]
  if (Math.hypot(point.x - previous.x, point.y - previous.y) < Math.max(1, activeAnnotationStroke.width / 5)) return
  activeAnnotationStroke.points.push({ x: point.x, y: point.y })
  renderAnnotationCanvas()
}

function endAnnotationPointer(event) {
  if (!activeAnnotationStroke) return
  event.preventDefault()
  annotationCanvas.value.releasePointerCapture?.(event.pointerId)
  activeAnnotationStroke = null
}

function startTextPlacement() {
  if (!annotationEditor.text.trim()) return
  annotationEditor.tool = 'text'
}

function undoAnnotation() {
  if (!annotationActions.value.length) return
  annotationActions.value.pop()
  activeAnnotationStroke = null
  renderAnnotationCanvas()
}

function clearAnnotations() {
  annotationActions.value = []
  activeAnnotationStroke = null
  renderAnnotationCanvas()
}

function renderAnnotationCanvas() {
  const canvas = annotationCanvas.value
  if (!canvas || !annotationBaseImage) return
  const context = canvas.getContext('2d')
  context.clearRect(0, 0, canvas.width, canvas.height)
  context.drawImage(annotationBaseImage, 0, 0, canvas.width, canvas.height)
  annotationActions.value.forEach((action) => {
    if (action.type === 'text') {
      context.save()
      context.font = `700 ${action.fontSize}px Arial, sans-serif`
      context.textBaseline = 'top'
      context.lineJoin = 'round'
      context.lineWidth = Math.max(2, action.fontSize / 12)
      context.strokeStyle = 'rgba(255,255,255,.9)'
      context.strokeText(action.text, action.x, action.y)
      context.fillStyle = action.color
      context.fillText(action.text, action.x, action.y)
      context.restore()
      return
    }
    const points = action.points || []
    if (!points.length) return
    context.save()
    context.strokeStyle = action.color
    context.fillStyle = action.color
    context.lineWidth = action.width
    context.lineCap = 'round'
    context.lineJoin = 'round'
    if (points.length === 1) {
      context.beginPath()
      context.arc(points[0].x, points[0].y, action.width / 2, 0, Math.PI * 2)
      context.fill()
    } else {
      context.beginPath()
      context.moveTo(points[0].x, points[0].y)
      points.slice(1).forEach((point) => context.lineTo(point.x, point.y))
      context.stroke()
    }
    context.restore()
  })
}

async function saveAnnotatedCopy() {
  const canvas = annotationCanvas.value
  const stop = annotationEditor.stop
  const slot = annotationEditor.slot
  const sourceFile = annotationEditor.file
  const preUpload = annotationEditor.mode === 'preupload'
  if (!canvas || !stop || !slot || (!preUpload && !annotationActions.value.length)) return
  annotationEditor.saving = true
  try {
    const blob = await compressedJpegBlob(canvas, 1400 * 1024)
    if (!blob) throw new Error('The annotated photo could not be created.')
    const sourceName = (sourceFile?.name || 'inspection-photo').replace(/\.[^.]+$/, '').replace(/^annotated-/, '')
    const fileName = preUpload
      ? `${sourceName}-${Date.now()}.jpg`
      : `annotated-${sourceName}-${Date.now()}.jpg`
    const file = new File([blob], fileName, { type: 'image/jpeg', lastModified: Date.now() })
    if (preUpload) {
      const hadAnnotations = annotationActions.value.length > 0
      const originalFile = annotationEditor.pendingFile || file
      queueBackgroundUpload({
        name: originalFile.name || file.name,
        size: originalFile.size || file.size,
        description: `${stop.customerName || 'Inspection'} · ${slot.label}`,
        mediaType: 'photo',
        upload: (onProgress) => uploadPreparedFile(stop, slot, file, originalFile, false, onProgress)
      })
      annotationEditor.saving = false
      closeAnnotationEditor()
      expandedId.value = stop.id
      showMessage(hadAnnotations
        ? 'Marked photo added to the background upload queue.'
        : 'Photo added to the background upload queue.', false)
      return
    }
    const data = new FormData()
    data.append('categoryKey', slot.key)
    data.append('file', file)
    const uploaded = await http.post(`/inspector/route/${stop.id}/photos`, data, { timeout: 0 })
    const uploadedFile = { id: uploaded.id, url: uploaded.url, name: file.name, remark: '' }
    // 将标注副本放在首位，让卡片立即显示刚保存的标注；原图仍保留在同一分类中。
    slot.files = [uploadedFile, ...(slot.files || [])]
    await loadPreview(uploadedFile)
    annotationEditor.saving = false
    closeAnnotationEditor()
    expandedId.value = stop.id
    showMessage('Annotated copy saved. The original photo is unchanged.', false)
  } catch (error) {
    annotationEditor.saving = false
    annotationEditor.error = error.message || 'The annotated photo could not be saved.'
  }
}

async function loadPreviews(stops) {
  // 视频不预下载为 Blob，避免打开路线页面时再次下载数 GB 文件。
  const photos = stops.flatMap((stop) => stop.photoSlots || [])
    .filter((slot) => !isVideoSlot(slot))
    .flatMap((slot) => slot.files || [])
  await Promise.all(photos.map(loadPreview))
}

async function loadPreview(photo) {
  if (previews[photo.id]) return
  try {
    const response = await fetch(photo.url, { headers: { Authorization: `Bearer ${authState.token}` } })
    if (response.ok) previews[photo.id] = URL.createObjectURL(await response.blob())
  } catch (_) {
    // 图片预览失败不影响路线和上传操作。
  }
}

async function compressInspectionPhoto(file) {
  if (!file.type.startsWith('image/') || file.size < 1024 * 1024 || /heic|heif|gif/i.test(file.type)) return file
  try {
    const bitmap = await createImageBitmap(file)
    const maximumDimension = 1600
    const scale = Math.min(1, maximumDimension / Math.max(bitmap.width, bitmap.height))
    const canvas = document.createElement('canvas')
    canvas.width = Math.max(1, Math.round(bitmap.width * scale))
    canvas.height = Math.max(1, Math.round(bitmap.height * scale))
    canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height)
    bitmap.close()
    const blob = await compressedJpegBlob(canvas, 1200 * 1024)
    if (!blob || blob.size >= file.size) return file
    const name = file.name.replace(/\.[^.]+$/, '') + '.jpg'
    return new File([blob], name, { type: 'image/jpeg', lastModified: Date.now() })
  } catch (_) {
    return file
  }
}

async function compressedJpegBlob(canvas, targetBytes) {
  // Reduce quality only as far as needed. This keeps labels and drawn notes readable
  // while making typical phone photos substantially faster to upload on mobile data.
  let smallest = null
  for (const quality of [0.82, 0.74, 0.66, 0.58]) {
    const blob = await new Promise((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality))
    if (!blob) continue
    smallest = blob
    if (blob.size <= targetBytes) break
  }
  return smallest
}

function uploadKey(stop, slot) { return `${stop.id}:${slot.key}` }
function isUploading(stop, slot) { return uploadProgress[uploadKey(stop, slot)] != null }
function isDeleting(file) { return Boolean(deleteProgress[file.id]) }
function uploadButtonText(stop, slot, idleText) {
  const progress = uploadProgress[uploadKey(stop, slot)]
  return progress == null ? idleText : `Uploading ${progress}%`
}
function formatBytes(bytes) {
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function previewUrl(slot) { return slot.files?.length ? previews[slot.files[0].id] : '' }
function photoCount(stop) { return (stop.photoSlots || []).reduce((sum, slot) => sum + (slot.files?.length || 0), 0) }
function standardPhotoSlots(stop) { return (stop.photoSlots || []).filter((slot) => slot.group === 'standard') }
function assignedPhotoProducts(stop) {
  const allowed = assignedProductKeys(stop?.projects)
  return photoProducts.filter((product) => allowed.includes(product.key))
}
function selectedPhotoProduct(stop) {
  return resolveAssignedProduct(stop?.projects, photoProductSelection[stop.id])
}
function setPhotoProduct(stop, productKey) {
  if (assignedProductKeys(stop?.projects).includes(productKey)) {
    photoProductSelection[stop.id] = productKey
  }
}
function checkAssignedUploadSlot(stop, slot) {
  const slots = selectedPhotoProduct(stop)
    ? [...productPhotoSections(stop).flatMap((section) => section.slots), ...additionalPhotoSlots(stop), ...videoSlots(stop)]
    : []
  if (slot && slots.some((item) => item.key === slot.key)) return true
  showMessage('This upload is not assigned to this job.', true)
  return false
}
function productPhotoSections(stop) {
  const slots = stop.photoSlots || []
  const productKey = selectedPhotoProduct(stop)
  if (!productKey) {
    return [{
      key: 'unassigned',
      title: 'No assigned product',
      description: 'Ask your scheduler to assign a product before uploading photos or videos.',
      slots: []
    }]
  }
  if (productKey === 'battery_solar') {
    return [
      {
        key: 'battery_sales',
        title: 'Sales Uploads',
        description: 'Customer and switchboard evidence supplied for the sale.',
        slots: slots.filter((slot) => batterySalesUploadKeys.has(slot.key))
      },
      {
        key: 'battery_cx',
        title: 'CX Battery Upload',
        description: 'Battery location, floor-plan support, video, solar-panel and approval evidence.',
        slots: slots.filter((slot) => slot.group === 'battery_solar' || slot.group === 'battery_video')
      }
    ]
  }
  if (productKey === 'heat_pump') {
    return [{
      key: 'heat_pump',
      title: 'Heat Pump Uploads',
      description: 'Heat-pump unit, location, connections and completion evidence.',
      slots: slots.filter((slot) => slot.group === 'heat_pump')
    }]
  }
  const productLabel = productKey === 'dac' ? 'DAC' : 'MAC'
  return [
    {
      key: productKey,
      title: `${productLabel} Uploads`,
      description: `Required ${productLabel} and ducted-system inspection evidence.`,
      slots: slots.filter((slot) => slot.group === 'standard' && slot.key !== 'drawn_floor_plan_measurements')
    },
    {
      key: `${productKey}_plans`,
      title: 'Floor Plan & Measurements',
      description: `Upload the drawn floor plan and the separate measurement document for the ${productLabel} inspection.`,
      slots: slots.filter((slot) => macPlanUploadKeys.has(slot.key))
    }
  ]
}
function ensureMacPlanUploadSlots(stops) {
  const definitions = [
    { key: 'mac_floor_plan', label: 'Floor Plan', group: 'mac_plan', files: [] },
    { key: 'mac_measurements', label: 'Measurements', group: 'mac_plan', files: [] }
  ]
  stops.forEach((stop) => {
    if (!projectFlags(stop).airConditioning) return
    if (!Array.isArray(stop.photoSlots)) stop.photoSlots = []
    definitions.forEach((definition) => {
      const existing = stop.photoSlots.find((slot) => slot.key === definition.key)
      if (existing) {
        existing.label = definition.label
      } else {
        stop.photoSlots.push({ ...definition, files: [] })
      }
    })
  })
}
function sectionFileCount(section) {
  return (section.slots || []).reduce((sum, slot) => sum + (slot.files?.length || 0), 0)
}
function additionalPhotoSlots(stop) {
  if (!selectedPhotoProduct(stop)) return []
  const limit = selectedPhotoProduct(stop) === 'dac' ? 12 : 10
  return (stop.photoSlots || []).filter((slot) => {
    const match = /^additional_photo_(\d+)$/.exec(slot.key)
    return slot.group === 'additional' && match && Number(match[1]) <= limit
  })
}
function videoLimit(stop) { return selectedPhotoProduct(stop) === 'dac' ? 9 : 4 }
function videoSlots(stop) {
  if (!selectedPhotoProduct(stop)) return []
  const limit = videoLimit(stop)
  return (stop.photoSlots || []).filter((slot) => {
    const match = /^inspection_video_(\d+)$/.exec(slot.key)
    return slot.group === 'video' && match && Number(match[1]) <= limit
  })
}
function isVideoSlot(slot) { return String(slot?.group || '').includes('video') }
function downloadVideoUrl(file) { return originalVideoDownloadUrl(file) }
async function openVideo(file, label) {
  activeVideo.open = true
  activeVideo.preparing = true
  activeVideo.status = 'Preparing video…'
  activeVideo.url = ''
  activeVideo.file = file
  activeVideo.label = label
  try {
    activeVideo.url = await prepareVideoPlayback(file, (status) => { activeVideo.status = status })
  } catch (requestError) {
    showMessage(requestError.message || 'The video could not be prepared.', true)
  } finally {
    activeVideo.preparing = false
  }
}
function closeVideo() {
  activeVideo.open = false
  activeVideo.preparing = false
  activeVideo.url = ''
  activeVideo.file = null
}
function videoPlaybackFailed() {
  showMessage('This video could not be played. Use Download original video instead.', true)
}
function projectFlags(stop) {
  const projects = String(stop?.projects || '').toLowerCase()
  const tokens = new Set(projects.split(/[^a-z0-9]+/).filter(Boolean))
  const batterySolar = tokens.has('battery') || tokens.has('sp') || projects.includes('solar')
  const heatPump = tokens.has('hp') || projects.includes('heat pump')
  const mac = tokens.has('mac') || (!tokens.has('dac') && !batterySolar && !heatPump)
  const dac = tokens.has('dac')
  return {
    batterySolar,
    heatPump,
    mac,
    dac,
    airConditioning: mac || dac
  }
}
function completionRequirements(stop) {
  const slots = stop.photoSlots || []
  const flags = projectFlags(stop)
  const requirements = []
  if (flags.airConditioning) {
    const product = flags.dac && !flags.mac ? 'dac' : 'mac'
    slots.filter((slot) => requiredPhotoKeys.has(slot.key)).forEach((slot) => requirements.push({
      key: slot.key,
      product,
      label: slot.label,
      slotKeys: [slot.key],
      satisfied: Boolean(slot.files?.length)
    }))
  }
  if (flags.batterySolar) {
    const batteryLocationKeys = ['battery_serial_label', 'battery_location']
    const switchboardKeys = ['switchboard', 'battery_unit']
    requirements.push({
      key: 'battery_switchboard_requirement',
      product: 'battery_solar',
      label: 'Switchboard photo',
      slotKeys: switchboardKeys,
      satisfied: slots.some((slot) => switchboardKeys.includes(slot.key) && slot.files?.length)
    })
    requirements.push({
      key: 'battery_location_requirement',
      product: 'battery_solar',
      label: 'Battery location photo (indoor or outdoor)',
      slotKeys: batteryLocationKeys,
      satisfied: slots.some((slot) => batteryLocationKeys.includes(slot.key) && slot.files?.length)
    })
  }
  return requirements
}
function missingRequiredPhotoSlots(stop) { return completionRequirements(stop).filter((item) => !item.satisfied) }
function requiredPhotoCount(stop) { return completionRequirements(stop).filter((item) => item.satisfied).length }
function requiredPhotoTotal(stop) { return completionRequirements(stop).length }
function requiredPhotosReady(stop) { return missingRequiredPhotoSlots(stop).length === 0 }
function isRequiredPhotoSlot(stop, slot) {
  return completionRequirements(stop).some((item) => item.slotKeys.includes(slot.key))
}
function requiredSlotSatisfied(stop, slot) {
  const requirement = completionRequirements(stop).find((item) => item.slotKeys.includes(slot.key))
  return !requirement || requirement.satisfied
}
function requiredSlotBadge(stop, slot) {
  const requirement = completionRequirements(stop).find((item) => item.slotKeys.includes(slot.key))
  if (requirement?.key === 'battery_location_requirement') return 'Indoor or outdoor required'
  if (requirement?.key === 'battery_switchboard_requirement') return 'Either switchboard photo required'
  return 'Required'
}
function selectedProductHasRequirements(stop) {
  return completionRequirements(stop).some((item) => item.product === selectedPhotoProduct(stop))
}
function requiredPhotoInstruction(stop) {
  if (selectedPhotoProduct(stop) === 'battery_solar') {
    return 'Required: the battery switchboard photo and one indoor or outdoor battery-location photo.'
  }
  return `All required ${selectedPhotoProduct(stop) === 'dac' ? 'DAC' : 'MAC'} photos must be uploaded before Job Done.`
}
function additionalPhotoCount(stop) {
  return additionalPhotoSlots(stop).reduce((sum, slot) => sum + (slot.files?.length || 0), 0)
}
function videoCount(stop) { return videoSlots(stop).filter((slot) => slot.files?.length).length }
function mapUrl(address) { return `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(address || '')}` }
function navigationUrl(address) { return `https://www.google.com/maps/dir/?api=1&destination=${encodeURIComponent(address || '')}&travelmode=driving&dir_action=navigate` }
function phoneUrl(phone) { return phone ? `tel:${String(phone).replace(/[^+\d]/g, '')}` : '#' }
function statusLabel(status) { return status === 'done' ? 'Done' : status === 'customer_unavailable' ? 'Unavailable' : 'Fixed' }
function showMessage(text, error) { message.value = text; hasError.value = error }
function localDateText(date) {
  const offset = date.getTimezoneOffset() * 60000
  return new Date(date.getTime() - offset).toISOString().slice(0, 10)
}

async function loadInspectorRouteMap() {
  const stops = (routeData.stops || []).filter((stop) => stop.address)
  mapError.value = ''
  if (!stops.length || !routeMapElement.value) {
    clearInspectorRouteMap()
    mapNotice.value = ''
    return
  }
  if (!googleMapsKey) {
    mapNotice.value = ''
    mapError.value = 'Google Maps is not configured. Use Open map on each job instead.'
    return
  }
  mapNotice.value = 'Loading the inspection route…'
  try {
    await ensureInspectorGoogleScript()
    await drawInspectorRoute(stops)
  } catch (error) {
    mapNotice.value = ''
    mapError.value = error.message || 'The route map could not be loaded.'
  }
}

function ensureInspectorGoogleScript() {
  if (window.google?.maps?.Map) return Promise.resolve()
  if (googleScriptPromise) return googleScriptPromise
  googleScriptPromise = new Promise((resolve, reject) => {
    window.gm_authFailure = () => reject(new Error('Google Maps authorization failed for this website.'))
    const existing = document.querySelector('script[data-google-maps="true"]')
    if (existing) {
      existing.addEventListener('load', resolve, { once: true })
      existing.addEventListener('error', () => reject(new Error('Google Maps script failed to load.')), { once: true })
      return
    }
    const script = document.createElement('script')
    script.dataset.googleMaps = 'true'
    script.src = `https://maps.googleapis.com/maps/api/js?key=${encodeURIComponent(googleMapsKey)}&libraries=places&language=en&region=AU`
    script.async = true
    script.defer = true
    script.onload = () => window.google?.maps?.Map ? resolve() : reject(new Error('Google Maps loaded without map support.'))
    script.onerror = () => reject(new Error('Google Maps script failed to load.'))
    document.head.appendChild(script)
  }).catch((error) => {
    googleScriptPromise = null
    throw error
  })
  return googleScriptPromise
}

async function drawInspectorRoute(stops) {
  clearInspectorRouteMap(false)
  const generation = ++routeMapGeneration
  googleRouteMap = googleRouteMap || new window.google.maps.Map(routeMapElement.value, {
    center: { lat: -37.8136, lng: 144.9631 },
    zoom: 10,
    mapTypeControl: false,
    streetViewControl: false,
    fullscreenControl: true
  })
  const bounds = new window.google.maps.LatLngBounds()
  const locatedStops = []
  let failedCount = 0
  for (let index = 0; index < stops.length; index += 1) {
    if (generation !== routeMapGeneration) return
    const position = await geocodeInspectorAddress(stops[index].address)
    if (!position) {
      failedCount += 1
      continue
    }
    locatedStops.push({ stop: stops[index], position, number: index + 1 })
    bounds.extend(position)
  }
  if (generation !== routeMapGeneration) return
  if (!locatedStops.length) throw new Error('No route addresses could be located. Use Open map on each job instead.')

  locatedStops.forEach(({ stop, position, number }) => {
    const isNext = nextStop.value?.id === stop.id
    const marker = new window.google.maps.Marker({
      map: googleRouteMap,
      position,
      label: { text: String(number), color: '#ffffff', fontSize: '12px', fontWeight: '700' },
      icon: {
        path: window.google.maps.SymbolPath.CIRCLE,
        fillColor: isNext ? '#16a34a' : '#2563eb',
        fillOpacity: 1,
        strokeColor: '#ffffff',
        strokeWeight: 2,
        scale: isNext ? 15 : 13
      },
      title: `${number}. ${stop.start || ''} ${stop.customerName || 'Inspection'}`
    })
    const info = new window.google.maps.InfoWindow({
      content: `<strong>${escapeMapHtml(number)}. ${escapeMapHtml(stop.start)} — ${escapeMapHtml(stop.customerName || 'Inspection')}</strong><br>${escapeMapHtml(stop.address)}<br><a href="${navigationUrl(stop.address)}" target="_blank" rel="noreferrer">Start navigation</a>`
    })
    marker.addListener('click', () => info.open({ anchor: marker, map: googleRouteMap }))
    googleRouteMarkers.push(marker)
  })

  if (locatedStops.length === 1) {
    googleRouteMap.setCenter(locatedStops[0].position)
    googleRouteMap.setZoom(14)
    mapNotice.value = 'One inspection is shown on the map.'
  } else {
    googleRouteMap.fitBounds(bounds)
    await drawDrivingRoute(locatedStops.map((item) => item.position))
    mapNotice.value = `Route loaded with ${locatedStops.length} jobs in inspection-time order.`
  }
  mapError.value = failedCount ? `${failedCount} address${failedCount === 1 ? '' : 'es'} could not be placed on the map.` : ''
}

async function drawDrivingRoute(positions) {
  const service = new window.google.maps.DirectionsService()
  try {
    const result = await new Promise((resolve, reject) => {
      service.route({
        origin: positions[0],
        destination: positions[positions.length - 1],
        waypoints: positions.slice(1, -1).map((location) => ({ location, stopover: true })),
        optimizeWaypoints: false,
        travelMode: window.google.maps.TravelMode.DRIVING
      }, (response, status) => status === 'OK' ? resolve(response) : reject(new Error(status)))
    })
    googleDirectionsRenderer = new window.google.maps.DirectionsRenderer({
      map: googleRouteMap,
      directions: result,
      suppressMarkers: true,
      preserveViewport: true,
      polylineOptions: { strokeColor: '#2563eb', strokeOpacity: 0.85, strokeWeight: 5 }
    })
  } catch (_) {
    googleRouteLine = new window.google.maps.Polyline({
      map: googleRouteMap,
      path: positions,
      strokeColor: '#2563eb',
      strokeOpacity: 0.8,
      strokeWeight: 4
    })
  }
}

async function geocodeInspectorAddress(address) {
  const cacheKey = 'scheduleSmartuserGeocodeCache'
  const cache = readInspectorGeocodeCache(cacheKey)
  if (cache[address]) return cache[address]
  const query = /\baustralia\b/i.test(address) ? address : `${address}, Australia`
  const geocoder = new window.google.maps.Geocoder()
  try {
    const results = await new Promise((resolve, reject) => {
      geocoder.geocode({ address: query, componentRestrictions: { country: 'AU' } }, (items, status) => {
        if (status === 'OK' && items?.length) resolve(items)
        else reject(new Error(status || 'UNKNOWN_ERROR'))
      })
    })
    const location = results[0].geometry.location
    const position = { lat: location.lat(), lng: location.lng() }
    cache[address] = position
    localStorage.setItem(cacheKey, JSON.stringify(cache))
    return position
  } catch (_) {
    return null
  }
}

function readInspectorGeocodeCache(cacheKey) {
  try { return JSON.parse(localStorage.getItem(cacheKey) || '{}') } catch (_) { return {} }
}

function clearInspectorRouteMap(resetMap = true) {
  routeMapGeneration += 1
  googleRouteMarkers.forEach((marker) => marker.setMap(null))
  googleRouteMarkers = []
  if (googleDirectionsRenderer) googleDirectionsRenderer.setMap(null)
  googleDirectionsRenderer = null
  if (googleRouteLine) googleRouteLine.setMap(null)
  googleRouteLine = null
  if (resetMap) googleRouteMap = null
}

function escapeMapHtml(value) {
  return String(value || '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;')
}
</script>
