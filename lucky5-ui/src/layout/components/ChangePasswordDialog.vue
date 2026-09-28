<script lang="ts" setup>
import type { FormInstance, FormRules } from 'element-plus'
import { updateUserPassword } from '@/api/system/user/profile'

defineOptions({ name: 'ChangePasswordDialog' })

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

const message = useMessage()
const formRef = ref<FormInstance>()
const submitting = ref(false)
const form = reactive({
  oldPassword: '',
  newPassword: ''
})

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const rules: FormRules = {
  oldPassword: [
    { required: true, message: '请输入原密码', trigger: 'blur' },
    { min: 4, max: 16, message: '密码长度为 4 到 16 位', trigger: 'blur' }
  ],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 4, max: 16, message: '密码长度为 4 到 16 位', trigger: 'blur' }
  ]
}

const reset = () => {
  formRef.value?.resetFields()
}

const submit = async () => {
  if (!formRef.value) return
  await formRef.value.validate()
  submitting.value = true
  try {
    await updateUserPassword(form.oldPassword, form.newPassword)
    message.success('密码修改成功')
    visible.value = false
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    class="lucky-change-password-dialog"
    title="修改密码"
    width="565px"
    @closed="reset"
  >
    <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
      <el-form-item label="原密码" prop="oldPassword">
        <el-input
          v-model="form.oldPassword"
          autocomplete="current-password"
          placeholder="原密码"
          show-password
          type="password"
        />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input
          v-model="form.newPassword"
          autocomplete="new-password"
          placeholder="新密码"
          show-password
          type="password"
        />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">确定</el-button>
    </template>
  </el-dialog>
</template>
