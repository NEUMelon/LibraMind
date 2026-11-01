<script setup lang="ts">
// 副本表单：流通状态只能维护非在借状态，借出/归还必须走流通台。
import { reactive, ref, watch } from 'vue'
import { LoaderCircle } from 'lucide-vue-next'
import { copies as copyApi } from '@/api/endpoints'
import type { CirculationStatus, CopyInput, CopyView } from '@/api/types'
import { fieldError } from '@/lib/forms'
import Modal from '@/components/Modal.vue'
import FormErrors from '@/components/FormErrors.vue'

const props = defineProps<{ open: boolean; copy: CopyView | null; editionId: number; isbn?: string; nextIndex?: number }>()
const emit = defineEmits<{ close: []; saved: [c: CopyView] }>()
const form = reactive<CopyInput>({ accessionNo: '', editionId: 0, callNumber: '', location: '', circulationStatus: 'AVAILABLE' })
const busy = ref(false)
const error = ref<unknown>(null)

watch(
  () => props.open,
  (o) => {
    if (!o) return
    error.value = null
    const c = props.copy
    Object.assign(
      form,
      c
        ? { accessionNo: c.accessionNo, editionId: c.editionId, callNumber: c.callNumber, location: c.location, circulationStatus: c.circulationStatus }
        : {
            accessionNo: props.isbn ? `LM-${props.isbn}-${String(props.nextIndex ?? 1).padStart(2, '0')}` : '',
            editionId: props.editionId,
            callNumber: '',
            location: '',
            circulationStatus: 'AVAILABLE' as CirculationStatus,
          },
    )
  },
)
async function save() {
  busy.value = true
  error.value = null
  try {
    const input = { ...form, accessionNo: form.accessionNo.trim().toUpperCase() }
    const saved = props.copy ? await copyApi.update(props.copy.id, input) : await copyApi.create(input)
    emit('saved', saved)
    emit('close')
  } catch (e) {
    error.value = e
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Modal :open="open" :title="copy ? '编辑副本' : '新增副本'" width="520px" @close="emit('close')">
    <form id="copy-form" class="form-grid" novalidate @submit.prevent="save">
      <div class="field span-2">
        <label for="cf-acc" class="req">馆藏登录号</label>
        <input id="cf-acc" v-model="form.accessionNo" class="input mono" autocomplete="off" :aria-invalid="!!fieldError(error, 'accessionNo')" />
        <span v-if="fieldError(error, 'accessionNo')" class="error-text">{{ fieldError(error, 'accessionNo') }}</span>
        <span v-else class="hint">每一册唯一，贴在书上的条码就是它。</span>
      </div>
      <div class="field">
        <label for="cf-loc">位置</label>
        <input id="cf-loc" v-model="form.location" class="input" placeholder="例如 二层综合书库" />
      </div>
      <div class="field">
        <label for="cf-call">索书号</label>
        <input id="cf-call" v-model="form.callNumber" class="input mono" />
      </div>
      <div class="field span-2">
        <label for="cf-status" class="req">流通状态</label>
        <select id="cf-status" v-model="form.circulationStatus" class="select" :disabled="copy?.circulationStatus === 'BORROWED'">
          <option value="AVAILABLE">在架可借</option>
          <option value="MAINTENANCE">维护中</option>
          <option value="OFF_SHELF">下架</option>
          <option v-if="copy?.circulationStatus === 'BORROWED'" value="BORROWED">已借出</option>
        </select>
        <span class="hint">{{ copy?.circulationStatus === 'BORROWED' ? '这一册在借，归还请到流通台办理。' : '借出和归还只能在流通台办理。' }}</span>
      </div>
      <div class="span-2"><FormErrors :error="error" /></div>
    </form>
    <template #foot>
      <button class="btn" type="button" @click="emit('close')">取消</button>
      <button class="btn btn-primary" type="submit" form="copy-form" :disabled="busy"><LoaderCircle v-if="busy" class="spin" />保存</button>
    </template>
  </Modal>
</template>
