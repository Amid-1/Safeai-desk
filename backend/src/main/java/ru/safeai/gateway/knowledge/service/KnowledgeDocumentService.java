package ru.safeai.gateway.knowledge.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import ru.safeai.gateway.audit.AuditEventType;
import ru.safeai.gateway.audit.service.AuditEventService;
import ru.safeai.gateway.common.exception.*;
import ru.safeai.gateway.common.security.SafeAiUserPrincipal;
import ru.safeai.gateway.knowledge.dto.*;
import ru.safeai.gateway.knowledge.entity.*;
import ru.safeai.gateway.knowledge.model.*;
import ru.safeai.gateway.knowledge.repository.*;
import ru.safeai.gateway.knowledge.storage.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service @RequiredArgsConstructor
public class KnowledgeDocumentService {
    private final KnowledgeBaseRepository bases;
    private final KnowledgeBaseMembershipRepository memberships;
    private final KnowledgeDocumentRepository documents;
    private final KnowledgeDocumentVersionRepository versions;
    private final KnowledgeIngestionJobRepository jobs;
    private final ObjectStorage storage;
    private final KnowledgeStorageProperties properties;
    private final AuditEventService audit;

    @Transactional(readOnly=true)
    public KnowledgeDocumentPageResponse list(UUID kbId,SafeAiUserPrincipal user,int page,int size){
        KnowledgeBaseAccessLevel access=authorize(kbId,user,KnowledgeBaseAccessLevel.VIEWER);
        PageRequest request=PageRequest.of(page,Math.min(size,100),Sort.by(Sort.Direction.DESC,"updatedAt"));
        Page<KnowledgeDocumentEntity> result=(isAdmin(user)||access==KnowledgeBaseAccessLevel.OWNER)
                ? documents.findAllByKnowledgeBaseIdAndOrganizationId(kbId,user.getOrganizationId(),request)
                : documents.findAllByKnowledgeBaseIdAndOrganizationIdAndEnabledTrue(kbId,user.getOrganizationId(),request);
        List<KnowledgeDocumentResponse> content=result.getContent().stream().map(this::response).toList();
        return new KnowledgeDocumentPageResponse(content,result.getNumber(),result.getSize(),result.getTotalElements(),result.getTotalPages());
    }

    @Transactional
    public KnowledgeDocumentResponse uploadNew(UUID kbId,String requestedName,MultipartFile file,SafeAiUserPrincipal user){
        authorize(kbId,user,KnowledgeBaseAccessLevel.EDITOR);
        String name=normalizeName(requestedName==null||requestedName.isBlank()?file.getOriginalFilename():requestedName);
        if(documents.existsByKnowledgeBaseIdAndOrganizationIdAndNameIgnoreCase(kbId,user.getOrganizationId(),name)) throw new ConflictException("Документ с таким названием уже существует.");
        KnowledgeDocumentEntity doc=new KnowledgeDocumentEntity(); doc.setOrganizationId(user.getOrganizationId()); doc.setKnowledgeBaseId(kbId); doc.setName(name); doc.setEnabled(true); doc.setCreatedByUserId(user.getId()); documents.saveAndFlush(doc);
        KnowledgeDocumentResponse result=storeVersion(doc,file,user);
        audit.record(user,user.getOrganizationId(),AuditEventType.KNOWLEDGE_DOCUMENT_CREATED,Map.of("knowledgeBaseId",kbId.toString(),"documentId",doc.getId().toString(),"name",name));
        return result;
    }

    @Transactional
    public KnowledgeDocumentResponse uploadVersion(UUID kbId,UUID documentId,MultipartFile file,SafeAiUserPrincipal user){
        authorize(kbId,user,KnowledgeBaseAccessLevel.EDITOR);
        KnowledgeDocumentEntity doc=requireDocument(kbId,documentId,user.getOrganizationId());
        return storeVersion(doc,file,user);
    }

    @Transactional
    public Download download(UUID kbId,UUID documentId,UUID versionId,SafeAiUserPrincipal user){
        authorize(kbId,user,KnowledgeBaseAccessLevel.VIEWER);
        KnowledgeDocumentEntity doc=requireDocument(kbId,documentId,user.getOrganizationId());
        if(!doc.isEnabled() && !isAdmin(user)) throw new ResourceNotFoundException("Документ не найден.");
        UUID selected=versionId==null?doc.getCurrentVersionId():versionId;
        KnowledgeDocumentVersionEntity version=versions.findByIdAndDocumentIdAndKnowledgeBaseIdAndOrganizationId(selected,documentId,kbId,user.getOrganizationId()).orElseThrow(()->new ResourceNotFoundException("Версия документа не найдена."));
        try {
            Download result=new Download(storage.get(version.getStorageKey()),version.getOriginalFilename(),version.getMediaType());
            audit.record(user,user.getOrganizationId(),AuditEventType.KNOWLEDGE_DOCUMENT_DOWNLOADED,Map.of("knowledgeBaseId",kbId.toString(),"documentId",documentId.toString(),"documentVersionId",version.getId().toString()));
            return result;
        }
        catch(IOException e){ throw new ResourceNotFoundException("Файл документа недоступен."); }
    }

    private KnowledgeDocumentResponse storeVersion(KnowledgeDocumentEntity doc,MultipartFile file,SafeAiUserPrincipal user){
        byte[] bytes=validate(file); int number=documents.currentVersionNumber(doc.getId(),user.getOrganizationId())+1;
        UUID versionId=UUID.randomUUID(); String key=user.getOrganizationId()+"/"+doc.getKnowledgeBaseId()+"/"+doc.getId()+"/"+versionId;
        try { storage.put(key,new ByteArrayInputStream(bytes)); } catch(IOException e){ throw new BadRequestException("Не удалось сохранить файл.",e); }
        try {
            KnowledgeDocumentVersionEntity version=new KnowledgeDocumentVersionEntity(); version.setId(versionId); version.setOrganizationId(user.getOrganizationId()); version.setKnowledgeBaseId(doc.getKnowledgeBaseId()); version.setDocumentId(doc.getId()); version.setVersionNumber(number); version.setOriginalFilename(safeFilename(file.getOriginalFilename())); version.setMediaType(detectType(bytes)); version.setSizeBytes(bytes.length); version.setSha256(sha256(bytes)); version.setStorageKey(key); version.setCreatedByUserId(user.getId()); versions.saveAndFlush(version);
            KnowledgeIngestionJobEntity job=new KnowledgeIngestionJobEntity(); job.setOrganizationId(user.getOrganizationId()); job.setKnowledgeBaseId(doc.getKnowledgeBaseId()); job.setDocumentId(doc.getId()); job.setDocumentVersionId(versionId); job.setStatus(KnowledgeIngestionStatus.PENDING); jobs.saveAndFlush(job);
            doc.setCurrentVersionId(versionId); documents.saveAndFlush(doc);
            audit.record(user,user.getOrganizationId(),AuditEventType.KNOWLEDGE_DOCUMENT_VERSION_UPLOADED,Map.of("documentId",doc.getId().toString(),"documentVersionId",versionId.toString(),"versionNumber",number,"sha256",version.getSha256()));
            return KnowledgeDocumentResponse.from(doc,version,job.getStatus());
        } catch(RuntimeException e){ try{storage.delete(key);}catch(IOException ignored){} throw e; }
    }

    private KnowledgeDocumentResponse response(KnowledgeDocumentEntity doc){
        KnowledgeDocumentVersionEntity v=doc.getCurrentVersionId()==null?null:versions.findById(doc.getCurrentVersionId()).orElse(null);
        KnowledgeIngestionStatus status=v==null?null:jobs.findByDocumentVersionIdAndOrganizationId(v.getId(),doc.getOrganizationId()).map(KnowledgeIngestionJobEntity::getStatus).orElse(null);
        return KnowledgeDocumentResponse.from(doc,v,status);
    }
    private KnowledgeBaseAccessLevel authorize(UUID kbId,SafeAiUserPrincipal user,KnowledgeBaseAccessLevel required){
        KnowledgeBaseEntity kb=bases.findByIdAndOrganizationId(kbId,user.getOrganizationId()).orElseThrow(()->new ResourceNotFoundException("База знаний не найдена."));
        if(isAdmin(user)) return KnowledgeBaseAccessLevel.OWNER;
        if(!kb.isEnabled()) throw new ResourceNotFoundException("База знаний не найдена.");
        KnowledgeBaseAccessLevel actual=memberships.findByKnowledgeBaseIdAndOrganizationIdAndUserId(kbId,user.getOrganizationId(),user.getId()).map(KnowledgeBaseMembershipEntity::getAccessLevel).orElse(kb.getVisibility()==KnowledgeBaseVisibility.ORGANIZATION?KnowledgeBaseAccessLevel.VIEWER:null);
        if(actual==null || rank(actual)<rank(required)) throw new ForbiddenOperationException("Недостаточно прав для операции с документом.");
        return actual;
    }
    private static int rank(KnowledgeBaseAccessLevel level){ return switch(level){case VIEWER->1;case EDITOR->2;case OWNER->3;}; }
    private static boolean isAdmin(SafeAiUserPrincipal user){ return user.getAuthorities().stream().anyMatch(a->a.getAuthority().equals("ROLE_ADMIN")); }
    private KnowledgeDocumentEntity requireDocument(UUID kb,UUID id,UUID org){ return documents.findByIdAndKnowledgeBaseIdAndOrganizationId(id,kb,org).orElseThrow(()->new ResourceNotFoundException("Документ не найден.")); }
    private byte[] validate(MultipartFile file){ try { if(file==null||file.isEmpty()) throw new BadRequestException("Выберите непустой файл."); if(file.getSize()>properties.maxUploadBytes()) throw new BadRequestException("Размер файла превышает 25 МБ."); byte[] bytes=file.getBytes(); detectType(bytes); return bytes; } catch(IOException e){ throw new BadRequestException("Не удалось прочитать файл.",e); } }
    static String detectType(byte[] b){ if(b.length>=5&&b[0]=='%'&&b[1]=='P'&&b[2]=='D'&&b[3]=='F'&&b[4]=='-')return "application/pdf"; if(isDocx(b))return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; String head=new String(b,0,Math.min(b.length,512),java.nio.charset.StandardCharsets.UTF_8).stripLeading().toLowerCase(Locale.ROOT); if(head.startsWith("<!doctype html")||head.startsWith("<html"))return "text/html"; if(head.chars().noneMatch(c->c<9||(c>13&&c<32)))return "text/plain"; throw new BadRequestException("Поддерживаются только PDF, DOCX, TXT и HTML."); }
    private static boolean isDocx(byte[] bytes){
        if(bytes.length<4||bytes[0]!='P'||bytes[1]!='K'||bytes[2]!=3||bytes[3]!=4)return false;
        boolean contentTypes=false,document=false;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ ZipEntry entry; int entries=0; while((entry=zip.getNextEntry())!=null&&entries++<10_000){ String name=entry.getName(); contentTypes|="[Content_Types].xml".equals(name); document|="word/document.xml".equals(name); if(contentTypes&&document)return true; } }catch(IOException ignored){}
        return false;
    }
    private static String normalizeName(String v){ if(v==null) throw new BadRequestException("Введите название документа."); String n=v.strip(); if(n.isEmpty()||n.length()>255||n.chars().anyMatch(Character::isISOControl)) throw new BadRequestException("Некорректное название документа."); return n; }
    private static String safeFilename(String value){ if(value==null||value.isBlank()) return "document"; String v=value.replace('\\','/'); v=v.substring(v.lastIndexOf('/')+1).strip(); return normalizeName(v); }
    private static String sha256(byte[] bytes){ try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);} }
    public record Download(StoredObject object,String filename,String mediaType){}
}
