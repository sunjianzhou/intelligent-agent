<template>
  <div class="tag-input-wrap">
    <el-tag
      v-for="(t, i) in (modelValue || [])"
      :key="i"
      closable
      size="small"
      style="margin:2px"
      @close="remove(i)"
    >{{ t }}</el-tag>
    <el-input
      v-model="inputVal"
      size="small"
      :placeholder="placeholder"
      style="width:150px;margin:2px"
      @keydown.enter.prevent="onEnter"
    />
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElTag, ElInput } from 'element-plus'

const props = defineProps({
  modelValue: Array,
  placeholder: String,
})
const emit = defineEmits(['update:modelValue'])

const inputVal = ref('')

function onEnter() {
  const v = inputVal.value.trim()
  if (!v) return
  emit('update:modelValue', [...(props.modelValue || []), v])
  inputVal.value = ''
}

function remove(i) {
  const tags = [...(props.modelValue || [])]
  tags.splice(i, 1)
  emit('update:modelValue', tags)
}
</script>
